package dev.sentinellm;

import org.springframework.core.env.Environment;

final class RuntimeState implements AutoCloseable {
    final DurableState durable;
    final Audit audit;
    final RateLimits rates;
    RuntimeState(Settings settings,Contracts contracts,Environment env) {
        String mode=env.getProperty("SENTINEL_STATE_MODE","memory");
        if(mode.equals("memory")){durable=null;audit=new FileAudit(settings.auditPath());rates=new LocalRateLimits();return;}
        if(!mode.equals("postgres"))throw new IllegalStateException("Invalid state mode.");
        if(!settings.policyPath().isBlank())throw new IllegalStateException("Policy file overrides are available only in memory mode; persistent policies use management revisions.");
        var pool=Database.pool(env.getRequiredProperty("SENTINEL_DB_URL"),env.getProperty("SENTINEL_DB_USER","sentinel_app"),env.getRequiredProperty("SENTINEL_DB_APP_PASSWORD"));
        PgState store=new PgState(pool,contracts,settings.identities().values());
        try {rates=new RedisRateLimits(env.getProperty("SENTINEL_REDIS_HOST","redis"),Integer.parseInt(env.getProperty("SENTINEL_REDIS_PORT","6379")),env.getRequiredProperty("SENTINEL_REDIS_PASSWORD"));}
        catch(Exception ex){store.close();throw new IllegalStateException("State startup dependency unavailable.");}
        durable=store;audit=store;
    }
    public void close(){try{rates.close();}finally{if(durable!=null)durable.close();}}
}
