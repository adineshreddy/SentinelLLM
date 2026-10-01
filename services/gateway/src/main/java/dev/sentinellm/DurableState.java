package dev.sentinellm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.zaxxer.hikari.*;
import org.flywaydb.core.Flyway;
import java.sql.*;
import java.util.*;

record Snapshot(long revision,JsonNode policy,ToolRegistry registry) {}

/** State is mandatory in postgres mode: no stale configuration or file fallback. */
interface DurableState extends AutoCloseable {
    Snapshot snapshot(Identity identity);
    void start(String operation,Identity identity,String kind,Snapshot snapshot);
    void finish(String operation,Identity identity,int status,String code);
    ObjectNode configuration(Identity identity);
    ObjectNode update(Identity identity,JsonNode request);
    ObjectNode history(Identity identity,long before,int limit);
    ObjectNode events(Identity identity,long before,int limit,String action,String stage,String operation);
    ObjectNode trace(Identity identity,String operation);
    void close();
}

final class Database {
    static HikariDataSource pool(String url,String user,String password) {
        if (!url.matches("jdbc:postgresql://[A-Za-z0-9_.:-]+/[A-Za-z0-9_-]+") || password.length()<32 || !user.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) throw new IllegalArgumentException("Invalid database configuration.");
        HikariConfig config=new HikariConfig();config.setJdbcUrl(url);config.setUsername(user);config.setPassword(password);
        config.setMaximumPoolSize(8);config.setMinimumIdle(0);config.setConnectionTimeout(2500);config.setValidationTimeout(1000);
        config.addDataSourceProperty("connectTimeout","2");config.addDataSourceProperty("socketTimeout","5");config.addDataSourceProperty("tcpKeepAlive","true");
        config.setPoolName("sentinel-state");return new HikariDataSource(config);
    }
    static void migrate(String url,String user,String password) {
        try(var pool=pool(url,user,password)) {Flyway.configure().dataSource(pool).locations("classpath:db/migration").cleanDisabled(true).load().migrate();}
        catch(Exception ex) {throw new IllegalStateException("Database migration failed.");}
    }
}

/** Every transaction uses a tenant context for PostgreSQL RLS as well as explicit predicates. */
final class PgState implements DurableState,Audit {
    private final HikariDataSource pool;
    private final Contracts contracts;
    private final JsonNode canonicalRegistry;
    PgState(HikariDataSource pool,Contracts contracts,Collection<Identity> identities) {
        this.pool=pool;this.contracts=contracts;canonicalRegistry=contracts.resource("policies/tool-registry-v1.json");
        try {
            for(String tenant:new HashSet<>(identities.stream().map(Identity::tenant).toList())) seed(tenant);
        } catch(Exception ex){pool.close();throw new IllegalStateException("Database startup dependency unavailable.");}
    }
    @FunctionalInterface interface Work<T> {T run(Connection connection) throws Exception;}
    private <T> T transaction(String tenant,Work<T> work) {
        try(Connection connection=pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try(var q=connection.prepareStatement("SELECT set_config('sentinel.tenant', ?, true), set_config('statement_timeout','3000',true), set_config('lock_timeout','2000',true)")){q.setString(1,tenant);q.execute();}
                T result=work.run(connection);connection.commit();return result;
            } catch(Exception ex){try{connection.rollback();}catch(Exception ignored){}if(ex instanceof ApiFailure a)throw a;throw ApiFailure.unavailable();}
        } catch(ApiFailure ex){throw ex;}catch(Exception ex){throw ApiFailure.unavailable();}
    }
    private void seed(String tenant) {
        var policy=(ObjectNode)contracts.resource("policies/support-default-v1.json");policy.put("tenant_id",tenant);
        validate(tenant,policy,canonicalRegistry);
        transaction(tenant,c->{
            try(var q=c.prepareStatement("INSERT INTO config_versions(tenant_id,revision,policy,registry,actor_application) VALUES (?,1,?::jsonb,?::jsonb,'bootstrap') ON CONFLICT DO NOTHING")){
                q.setString(1,tenant);q.setString(2,policy.toString());q.setString(3,canonicalRegistry.toString());q.executeUpdate();}
            try(var q=c.prepareStatement("INSERT INTO tenant_heads(tenant_id,revision) VALUES (?,1) ON CONFLICT DO NOTHING")){q.setString(1,tenant);q.executeUpdate();}return null;
        });
    }
    private ToolRegistry validate(String tenant,JsonNode policy,JsonNode registry) {
        contracts.validate("policy",policy,false);contracts.validate("registry",registry,false);
        if(!tenant.equals(policy.path("tenant_id").asText()) || policy.toString().length()+registry.toString().length()>65536) throw ApiFailure.invalid();
        // This phase permits enabling/disabling reviewed definitions, not onboarding arbitrary executors/schemas.
        for(JsonNode tool:registry.path("tools")) {
            JsonNode canonical=null;for(JsonNode candidate:canonicalRegistry.path("tools")) if(candidate.path("tool_id").equals(tool.path("tool_id"))) canonical=candidate;
            if(canonical==null || !canonical.equals(tool)) throw ApiFailure.invalid();
        }
        for(JsonNode schema:registry.path("output_schemas")) {
            boolean known=false;for(JsonNode candidate:canonicalRegistry.path("output_schemas")) if(candidate.equals(schema))known=true;
            if(!known) throw ApiFailure.invalid();
        }
        try {ToolRegistry compiled=new ToolRegistry(contracts,registry);compiled.policy(policy);return compiled;}
        catch(Exception ex){throw ApiFailure.invalid();}
    }
    private ObjectNode configuration(Connection c,String tenant,boolean lock) throws Exception {
        if(lock) try(var q=c.prepareStatement("SELECT revision FROM tenant_heads WHERE tenant_id=? FOR UPDATE")){q.setString(1,tenant);try(var rows=q.executeQuery()){if(!rows.next())throw new ApiFailure(404,"NOT_FOUND");}}
        try(var q=c.prepareStatement("SELECT v.revision,v.policy::text,v.registry::text FROM tenant_heads h JOIN config_versions v ON v.tenant_id=h.tenant_id AND v.revision=h.revision WHERE h.tenant_id=?")){
            q.setString(1,tenant);try(var rows=q.executeQuery()){
                if(!rows.next())throw new ApiFailure(404,"NOT_FOUND");
                var result=Contracts.object().put("revision",rows.getLong(1));result.set("policy",Contracts.parse(rows.getString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8),true));result.set("registry",Contracts.parse(rows.getString(3).getBytes(java.nio.charset.StandardCharsets.UTF_8),true));return result;
            }
        }
    }
    public ObjectNode configuration(Identity identity){return transaction(identity.tenant(),c->configuration(c,identity.tenant(),false));}
    public Snapshot snapshot(Identity identity) {
        ObjectNode value=configuration(identity);
        try {return new Snapshot(value.path("revision").asLong(),value.path("policy"),validate(identity.tenant(),value.path("policy"),value.path("registry")));}
        catch(Exception ex){throw ApiFailure.unavailable();}
    }
    public ObjectNode update(Identity identity,JsonNode request) {
        contracts.validate("config-update",request,false);
        ToolRegistry ignored=validate(identity.tenant(),request.path("policy"),request.path("registry"));
        return transaction(identity.tenant(),c->{
            var current=configuration(c,identity.tenant(),true);long revision=current.path("revision").asLong();
            if(request.path("expected_revision").asLong()!=revision)throw new ApiFailure(409,"VERSION_CONFLICT");
            if(revision>=Integer.MAX_VALUE || !request.path("policy").path("id").equals(current.path("policy").path("id"))
                    || request.path("policy").path("version").asLong()!=revision+1 || request.path("registry").path("version").asLong()!=revision+1)throw ApiFailure.invalid();
            try(var q=c.prepareStatement("INSERT INTO config_versions(tenant_id,revision,policy,registry,actor_application) VALUES (?,?,?::jsonb,?::jsonb,?)")){
                q.setString(1,identity.tenant());q.setLong(2,revision+1);q.setString(3,request.path("policy").toString());q.setString(4,request.path("registry").toString());q.setString(5,identity.application());q.executeUpdate();}
            try(var q=c.prepareStatement("UPDATE tenant_heads SET revision=? WHERE tenant_id=?")){q.setLong(1,revision+1);q.setString(2,identity.tenant());if(q.executeUpdate()!=1)throw ApiFailure.unavailable();}
            try(var q=c.prepareStatement("INSERT INTO management_events(tenant_id,event_id,actor_application,previous_revision,revision) VALUES (?,?,?,?,?)")){
                q.setString(1,identity.tenant());q.setObject(2,UUID.randomUUID());q.setString(3,identity.application());q.setLong(4,revision);q.setLong(5,revision+1);q.executeUpdate();}
            return configuration(c,identity.tenant(),false);
        });
    }
    public ObjectNode history(Identity identity,long before,int limit) {
        return transaction(identity.tenant(),c->{
            var response=Contracts.object();var values=response.putArray("versions");
            try(var q=c.prepareStatement("SELECT revision,policy::text,registry::text,actor_application,created_at FROM config_versions WHERE tenant_id=? AND revision<? ORDER BY revision DESC LIMIT ?")){
                q.setString(1,identity.tenant());q.setLong(2,before);q.setInt(3,limit+1);try(var rows=q.executeQuery()){
                    while(rows.next()) {if(values.size()==limit){response.put("next_cursor",values.get(values.size()-1).path("revision").asText());break;}
                        var item=values.addObject().put("revision",rows.getLong(1)).put("actor_application",rows.getString(4)).put("created_at",rows.getTimestamp(5).toInstant().toString());
                        item.set("policy",Contracts.JSON.readTree(rows.getString(2)));item.set("registry",Contracts.JSON.readTree(rows.getString(3)));}
                }
            }return response;
        });
    }
    public void start(String operation,Identity identity,String kind,Snapshot snapshot) {
        long deadlineMs=snapshot.policy().path("limits").path("provider_timeout_ms").asLong()+3*snapshot.policy().path("limits").path("inspection_timeout_ms").asLong()+60000;
        transaction(identity.tenant(),c->{try(var q=c.prepareStatement("INSERT INTO operations(tenant_id,operation_id,application_id,kind,revision,status,deadline_at) VALUES (?,?,?,?,?,'started',clock_timestamp()+(? * interval '1 millisecond'))")){
            q.setString(1,identity.tenant());q.setObject(2,UUID.fromString(operation));q.setString(3,identity.application());q.setString(4,kind);q.setLong(5,snapshot.revision());q.setLong(6,deadlineMs);q.executeUpdate();}return null;});
    }
    public void beforeDispatch(String operation,Identity identity,String kind,String tool) {
        transaction(identity.tenant(),c->{try(var q=c.prepareStatement("UPDATE operations SET status='in_flight',external_state='dispatching',external_kind=?,tool_id=?,updated_at=clock_timestamp() WHERE tenant_id=? AND operation_id=? AND status='started'")){
            q.setString(1,kind);q.setString(2,tool);q.setString(3,identity.tenant());q.setObject(4,UUID.fromString(operation));if(q.executeUpdate()!=1)throw ApiFailure.unavailable();}return null;});
    }
    public void afterReturn(String operation,Identity identity) {
        transaction(identity.tenant(),c->{try(var q=c.prepareStatement("UPDATE operations SET external_state='returned',updated_at=clock_timestamp() WHERE tenant_id=? AND operation_id=? AND status='in_flight'")){
            q.setString(1,identity.tenant());q.setObject(2,UUID.fromString(operation));if(q.executeUpdate()!=1)throw ApiFailure.unavailable();}return null;});
    }
    public void finish(String operation,Identity identity,int status,String code) {
        transaction(identity.tenant(),c->{try(var q=c.prepareStatement("UPDATE operations SET status=CASE WHEN ?='OUTCOME_UNCERTAIN' OR (? >= 500 AND external_state='dispatching') THEN 'uncertain' WHEN ? >= 500 THEN 'failed' WHEN ? >= 400 THEN 'blocked' ELSE 'completed' END,http_status=?,error_code=?,updated_at=clock_timestamp() WHERE tenant_id=? AND operation_id=? AND status IN ('started','in_flight')")){
            q.setString(1,code);q.setInt(2,status);q.setInt(3,status);q.setInt(4,status);q.setInt(5,status);q.setString(6,code.isEmpty()?null:code);q.setString(7,identity.tenant());q.setObject(8,UUID.fromString(operation));if(q.executeUpdate()!=1)throw ApiFailure.unavailable();}return null;});
    }
    public void write(JsonNode decision,Identity identity,String provider,String outcome,long elapsedMs) {
        ObjectNode event=AuditEvents.build(decision,identity,provider,outcome,elapsedMs);contracts.validate("audit-event",event,true);
        transaction(identity.tenant(),c->{try(var q=c.prepareStatement("INSERT INTO audit_events(tenant_id,operation_id,event_id,event) VALUES (?,?,?,?::jsonb)")){
            q.setString(1,identity.tenant());q.setObject(2,UUID.fromString(event.path("operation_id").asText()));q.setObject(3,UUID.fromString(event.path("event_id").asText()));q.setString(4,event.toString());q.executeUpdate();}return null;});
    }
    public ObjectNode events(Identity identity,long before,int limit,String action,String stage,String operation) {
        return transaction(identity.tenant(),c->{
            var response=Contracts.object();var values=response.putArray("events");
            try(var q=c.prepareStatement("SELECT sequence,event::text FROM audit_events WHERE tenant_id=? AND sequence<? AND (?='' OR event->>'action'=?) AND (?='' OR event->>'stage'=?) AND (?='' OR operation_id::text=?) ORDER BY sequence DESC LIMIT ?")){
                q.setString(1,identity.tenant());q.setLong(2,before);q.setString(3,action);q.setString(4,action);q.setString(5,stage);q.setString(6,stage);q.setString(7,operation);q.setString(8,operation);q.setInt(9,limit+1);
                try(var rows=q.executeQuery()){long last=0;while(rows.next()){if(values.size()==limit){response.put("next_cursor",Long.toString(last));break;}last=rows.getLong(1);values.add(Contracts.JSON.readTree(rows.getString(2)));}}
            }return response;
        });
    }
    public ObjectNode trace(Identity identity,String operation) {
        UUID uuid;try{uuid=UUID.fromString(operation);if(!uuid.toString().equals(operation))throw ApiFailure.invalid();}catch(Exception ex){throw ApiFailure.invalid();}
        return transaction(identity.tenant(),c->{
            var response=Contracts.object();
            try(var q=c.prepareStatement("SELECT application_id,kind,revision,status,external_state,external_kind,tool_id,http_status,created_at,updated_at,deadline_at,deadline_at<clock_timestamp(),error_code FROM operations WHERE tenant_id=? AND operation_id=?")){
                q.setString(1,identity.tenant());q.setObject(2,uuid);try(var rows=q.executeQuery()){
                    if(!rows.next())throw new ApiFailure(404,"NOT_FOUND");
                    String stored=rows.getString(4),effective=stored;
                    if(Set.of("started","in_flight").contains(stored)&&rows.getBoolean(12))effective=stored.equals("in_flight")?"uncertain":"failed";
                    var value=response.putObject("operation").put("operation_id",operation).put("tenant_id",identity.tenant()).put("application_id",rows.getString(1)).put("kind",rows.getString(2)).put("revision",rows.getLong(3)).put("status",effective).put("stored_status",stored).put("external_state",rows.getString(5)).put("external_kind",rows.getString(6)).put("created_at",rows.getTimestamp(9).toInstant().toString()).put("updated_at",rows.getTimestamp(10).toInstant().toString()).put("deadline_at",rows.getTimestamp(11).toInstant().toString());
                    if(rows.getString(13)!=null)value.put("error_code",rows.getString(13));
                    if(rows.getString(7)!=null)value.put("tool_id",rows.getString(7));int http=rows.getInt(8);if(!rows.wasNull())value.put("http_status",http);
                }
            }
            var values=response.putArray("events");
            try(var q=c.prepareStatement("SELECT event::text FROM audit_events WHERE tenant_id=? AND operation_id=? ORDER BY sequence LIMIT 101")){
                q.setString(1,identity.tenant());q.setObject(2,uuid);try(var rows=q.executeQuery()){while(rows.next()){if(values.size()>=100)throw ApiFailure.unavailable();values.add(Contracts.JSON.readTree(rows.getString(1)));}}
            }return response;
        });
    }
    public void close(){pool.close();}
}
