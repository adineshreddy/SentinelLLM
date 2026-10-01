package dev.sentinellm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SENTINEL_STATE_TESTS",matches="1")
class StateIntegrationTest {
    @TempDir Path temp;
    static String env(String name){return Objects.requireNonNull(System.getenv(name));}
    final Contracts contracts=new Contracts();
    Identity a,b;
    PgState state;
    final List<RedisRateLimits> clients=new ArrayList<>();
    @BeforeAll static void migrate(){Database.migrate(env("SENTINEL_TEST_DB_URL"),"sentinel_owner",env("SENTINEL_TEST_DB_OWNER_PASSWORD"));}
    PgState reopen(){return new PgState(Database.pool(env("SENTINEL_TEST_DB_URL"),"sentinel_app",env("SENTINEL_TEST_DB_APP_PASSWORD")),contracts,List.of(a,b));}
    @BeforeEach void initialize(){String suffix=UUID.randomUUID().toString();a=new Identity("a-"+suffix,"support",Set.of("support_agent"),Set.of("gpt-oss-120b"));b=new Identity("b-"+suffix,"support",Set.of("support_agent"),Set.of("gpt-oss-120b"));state=reopen();}
    @AfterEach void cleanup(){clients.forEach(RedisRateLimits::close);if(state!=null)state.close();}
    String op(){return UUID.randomUUID().toString();}
    RedisRateLimits redis(){var client=new RedisRateLimits("127.0.0.1",Integer.parseInt(env("SENTINEL_TEST_REDIS_PORT")),env("SENTINEL_TEST_REDIS_PASSWORD"));clients.add(client);return client;}
    ObjectNode update(ObjectNode config){var n=Contracts.object().put("expected_revision",config.path("revision").asLong());var policy=(ObjectNode)config.path("policy").deepCopy();var registry=(ObjectNode)config.path("registry").deepCopy();policy.put("version",config.path("revision").asInt()+1);registry.put("version",config.path("revision").asInt()+1);n.set("policy",policy);n.set("registry",registry);return n;}
    @Test void atomic_versioned_update_and_stale_conflict(){
        Snapshot old=state.snapshot(a);ObjectNode request=update(state.configuration(a));var next=state.update(a,request);
        assertEquals(2,next.path("revision").asLong());assertEquals(1,old.policy().path("version").asInt());assertEquals(2,state.snapshot(a).registry().snapshot.path("version").asInt());
        assertEquals(409,assertThrows(ApiFailure.class,()->state.update(a,request)).status);
        assertEquals(2,state.history(a,Long.MAX_VALUE,10).path("versions").size());assertEquals(1,state.configuration(b).path("revision").asInt());
    }
    @Test void competing_updates_have_exactly_one_winner() throws Exception {
        var request=update(state.configuration(a));var barrier=new CyclicBarrier(2);var success=new AtomicInteger();var conflicts=new AtomicInteger();
        try(var executor=Executors.newFixedThreadPool(2)){
            List<Future<?>> futures=new ArrayList<>();for(int i=0;i<2;i++)futures.add(executor.submit(()->{try{barrier.await();state.update(a,request);success.incrementAndGet();}catch(ApiFailure ex){assertEquals(409,ex.status);conflicts.incrementAndGet();}catch(Exception ex){throw new AssertionError(ex);}}));for(var future:futures)future.get();
        }assertEquals(1,success.get());assertEquals(1,conflicts.get());assertEquals(2,state.history(a,Long.MAX_VALUE,10).path("versions").size());
    }
    @Test void invalid_registry_reference_or_foreign_tenant_never_activates(){
        var request=update(state.configuration(a));((ObjectNode)request.path("policy")).put("tenant_id",b.tenant());assertEquals(400,assertThrows(ApiFailure.class,()->state.update(a,request)).status);
        var remote=update(state.configuration(a));((ObjectNode)remote.path("registry").path("tools").get(0).path("input_schema")).put("$ref","https://evil.invalid/schema");assertEquals(400,assertThrows(ApiFailure.class,()->state.update(a,remote)).status);
        assertEquals(1,state.configuration(a).path("revision").asInt());
    }
    ObjectNode decision(String operation){var d=Contracts.object().put("decision_id",op()).put("operation_id",operation).put("policy_id","support-default").put("policy_version",1).put("stage","tool_input").put("action","allow");d.putArray("findings");d.putArray("reason_codes").add("CONTENT_APPROVED");return d;}
    @Test void durable_pending_decision_and_completed_outcome_survive_reopen(){
        String operation=op();state.start(operation,a,"tool",state.snapshot(a));state.write(decision(operation),a,"mock","approved",0);state.beforeDispatch(operation,a,"tool","kb.search");
        assertEquals("in_flight",state.trace(a,operation).path("operation").path("status").asText());state.afterReturn(operation,a);state.finish(operation,a,200,"");state.close();state=reopen();
        assertEquals("completed",state.trace(a,operation).path("operation").path("status").asText());assertEquals(1,state.trace(a,operation).path("events").size());
    }
    @Test void tenant_queries_and_rls_cannot_access_other_records() throws Exception {
        String operation=op();state.start(operation,a,"tool",state.snapshot(a));state.write(decision(operation),a,"mock","approved",0);
        assertEquals(404,assertThrows(ApiFailure.class,()->state.trace(b,operation)).status);assertEquals(0,state.events(b,Long.MAX_VALUE,100,"","",operation).path("events").size());
        try(var pool=Database.pool(env("SENTINEL_TEST_DB_URL"),"sentinel_app",env("SENTINEL_TEST_DB_APP_PASSWORD"));var c=pool.getConnection()){
            try(var q=c.createStatement();var rows=q.executeQuery("SELECT count(*) FROM audit_events")){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
            c.setAutoCommit(false);try(var q=c.prepareStatement("SELECT set_config('sentinel.tenant',?,true)")){q.setString(1,b.tenant());q.execute();}
            try(var q=c.prepareStatement("SELECT count(*) FROM audit_events WHERE tenant_id=?")){q.setString(1,a.tenant());try(var rows=q.executeQuery()){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}}
            var denied=assertThrows(SQLException.class,()->{try(var q=c.prepareStatement("INSERT INTO audit_events(tenant_id,operation_id,event_id,event) VALUES (?,?,?,'{}')")){q.setString(1,a.tenant());q.setObject(2,UUID.fromString(operation));q.setObject(3,UUID.randomUUID());q.executeUpdate();}});assertEquals("42501",denied.getSQLState());c.rollback();
        }
    }
    @Test void runtime_role_cannot_rewrite_audit_or_configuration() throws Exception {
        try(var pool=Database.pool(env("SENTINEL_TEST_DB_URL"),"sentinel_app",env("SENTINEL_TEST_DB_APP_PASSWORD"));var c=pool.getConnection()){
            for(String sql:List.of("DELETE FROM audit_events","UPDATE audit_events SET event='{}'","DELETE FROM config_versions","UPDATE config_versions SET actor_application='other'"))assertEquals("42501",assertThrows(SQLException.class,()->{try(var q=c.createStatement()){q.executeUpdate(sql);}}).getSQLState());
        }
    }
    @Test void bounded_event_and_history_pagination(){
        for(int i=0;i<3;i++){String operation=op();state.start(operation,a,"preview",state.snapshot(a));state.write(decision(operation),a,"none","approved",0);}
        var page=state.events(a,Long.MAX_VALUE,2,"allow","tool_input","");assertEquals(2,page.path("events").size());assertTrue(page.has("next_cursor"));assertEquals(1,state.events(a,Long.parseLong(page.path("next_cursor").asText()),2,"","","").path("events").size());
        state.update(a,update(state.configuration(a)));var versions=state.history(a,Long.MAX_VALUE,1);assertEquals(1,versions.path("versions").size());assertEquals(1,state.history(a,Long.parseLong(versions.path("next_cursor").asText()),1).path("versions").size());
    }
    GatewayEngine engine(Provider provider,Audit audit,RateLimits rates){
        var fixture=new GatewayEngineTest();fixture.temp=temp;var settings=new Settings(Map.of("a",a,"b",b),"http://localhost/internal/v1/inspect","synthetic", "mock","https://api.ai.it.ufl.edu/v1","",temp.resolve("audit.jsonl"),"","safe");
        return new GatewayEngine(settings,contracts,(id,s,t)->fixture.report(id,s),provider,audit,new ToolRegistry(contracts),new DemoTools(),state,rates);
    }
    ObjectNode request(String text){var n=Contracts.object().put("model","gpt-oss-120b");n.putArray("messages").addObject().put("role","user").put("content",text);return n;}
    @Test void in_flight_policy_snapshot_is_stable_but_next_request_observes_update(){
        AtomicInteger calls=new AtomicInteger();var e=engine((approved,timeout)->{calls.incrementAndGet();var change=update(state.configuration(a));((ObjectNode)change.path("policy").path("actions").path("pii")).put("prompt","deny").put("response","deny");state.update(a,change);return new MockProvider("alice@example.test").complete(approved,timeout);},state,new LocalRateLimits());
        var result=e.run("chat",request("safe"),a,op());assertEquals(1,result.path("sentinel").path("policy_version").asInt());assertEquals("[REDACTED]",result.path("choices").get(0).path("message").path("content").asText());
        assertEquals(403,assertThrows(ApiFailure.class,()->e.run("chat",request("alice@example.test"),a,op())).status);assertEquals(1,calls.get());
    }
    @Test void redis_atomic_quota_is_shared_across_clients() throws Exception {
        var one=redis();var two=redis();var policy=(ObjectNode)state.snapshot(a).policy().deepCopy();((ObjectNode)policy.path("limits")).put("requests_per_window",7).put("window_seconds",3600);
        var accepted=new AtomicInteger();var denied=new AtomicInteger();try(var executor=Executors.newFixedThreadPool(10)){List<Future<?>> futures=new ArrayList<>();for(int i=0;i<40;i++){final var client=i%2==0?one:two;futures.add(executor.submit(()->{try{client.check(a,policy,"work");accepted.incrementAndGet();}catch(ApiFailure ex){assertEquals(429,ex.status);denied.incrementAndGet();}}));}for(var future:futures)future.get();}
        assertEquals(7,accepted.get());assertEquals(33,denied.get());one.check(b,policy,"work");
    }
    @Test void configuration_revision_does_not_reset_shared_quota(){
        var one=redis();var policy=(ObjectNode)state.snapshot(a).policy().deepCopy();((ObjectNode)policy.path("limits")).put("requests_per_window",2).put("window_seconds",3600);one.check(a,policy,"work");one.check(a,policy,"work");policy.put("version",2);
        assertEquals(429,assertThrows(ApiFailure.class,()->redis().check(a,policy,"work")).status);
    }
    @Test void concurrency_leases_are_shared_and_release_capacity() throws Exception {
        var one=redis();var two=redis();JsonNode policy=state.snapshot(a).policy();var first=one.acquire(a,policy,"chat");var second=two.acquire(a,policy,"chat");assertEquals(429,assertThrows(ApiFailure.class,()->one.acquire(a,policy,"chat")).status);
        try(var other=two.acquire(b,policy,"chat")){assertNotNull(other);}first.close();try(var next=one.acquire(a,policy,"chat")){assertNotNull(next);}second.close();
    }
    @Test void durable_dispatch_failure_prevents_provider_execution(){
        AtomicInteger calls=new AtomicInteger();Audit broken=new Audit(){public void write(JsonNode d,Identity i,String p,String o,long t){state.write(d,i,p,o,t);}public void beforeDispatch(String o,Identity i,String k,String tool){throw ApiFailure.unavailable();}};
        String operation=op();var e=engine((body,t)->{calls.incrementAndGet();return new MockProvider("safe").complete(body,t);},broken,new LocalRateLimits());assertEquals(503,assertThrows(ApiFailure.class,()->e.run("chat",request("safe"),a,operation)).status);assertEquals(0,calls.get());assertEquals("failed",state.trace(a,operation).path("operation").path("status").asText());
    }
    @Test void returned_output_is_withheld_if_durable_audit_fails(){
        Audit broken=new Audit(){public void write(JsonNode d,Identity i,String p,String o,long t){if(d.path("stage").asText().equals("output"))throw ApiFailure.unavailable();state.write(d,i,p,o,t);}public void beforeDispatch(String o,Identity i,String k,String tool){state.beforeDispatch(o,i,k,tool);}public void afterReturn(String o,Identity i){state.afterReturn(o,i);}};
        String operation=op();var e=engine(new MockProvider("safe"),broken,new LocalRateLimits());assertEquals(503,assertThrows(ApiFailure.class,()->e.run("chat",request("safe"),a,operation)).status);assertEquals("returned",state.trace(a,operation).path("operation").path("external_state").asText());assertEquals("failed",state.trace(a,operation).path("operation").path("status").asText());
    }
    @Test void uncertain_dispatch_is_never_reported_as_completed(){
        String operation=op();state.start(operation,a,"tool",state.snapshot(a));state.beforeDispatch(operation,a,"tool","kb.search");state.finish(operation,a,503,"OUTCOME_UNCERTAIN");assertEquals("uncertain",state.trace(a,operation).path("operation").path("status").asText());assertEquals("dispatching",state.trace(a,operation).path("operation").path("external_state").asText());
    }
    @Test void expired_dispatch_is_reported_as_uncertain_without_reexecution() throws Exception {
        String operation=op();state.start(operation,a,"tool",state.snapshot(a));state.beforeDispatch(operation,a,"tool","kb.search");
        try(var pool=Database.pool(env("SENTINEL_TEST_DB_URL"),"sentinel_app",env("SENTINEL_TEST_DB_APP_PASSWORD"));var c=pool.getConnection()){
            c.setAutoCommit(false);try(var q=c.prepareStatement("SELECT set_config('sentinel.tenant',?,true)")){q.setString(1,a.tenant());q.execute();}
            try(var q=c.prepareStatement("UPDATE operations SET deadline_at=clock_timestamp()-interval '1 second' WHERE tenant_id=? AND operation_id=?")){q.setString(1,a.tenant());q.setObject(2,UUID.fromString(operation));q.executeUpdate();}c.commit();
        }
        var trace=state.trace(a,operation);assertEquals("uncertain",trace.path("operation").path("status").asText());assertEquals("in_flight",trace.path("operation").path("stored_status").asText());
    }
    @Test void disabling_registry_tool_and_permissions_is_one_atomic_revision() {
        var change=update(state.configuration(a));
        ((com.fasterxml.jackson.databind.node.ArrayNode)change.path("registry").path("tools")).remove(1);
        ((ObjectNode)change.path("policy").path("roles")).putArray("support_agent").add("kb.search");
        state.update(a,change);
        var e=engine(new MockProvider("safe"),state,new LocalRateLimits());
        assertEquals(1,e.run("discovery",null,a,op()).path("tools").size());
        String operation=op();var request=Contracts.object().put("tool_id","ticket.get");request.putObject("arguments").put("ticket_id","DEMO-1");
        assertEquals(403,assertThrows(ApiFailure.class,()->e.run("tool",request,a,operation)).status);
        var trace=state.trace(a,operation);assertEquals("none",trace.path("operation").path("external_kind").asText());assertEquals("POLICY_DENIED",trace.path("operation").path("error_code").asText());
    }
}
