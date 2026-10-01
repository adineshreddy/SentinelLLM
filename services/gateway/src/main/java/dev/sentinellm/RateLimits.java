package dev.sentinellm;

import com.fasterxml.jackson.databind.JsonNode;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

interface RateLimits extends AutoCloseable {
    void check(Identity identity,JsonNode policy,String scope);
    default AutoCloseable acquire(Identity identity,JsonNode policy,String kind) {return ()->{};}
    default void ready() {}
    default void close() {}
}

final class LocalRateLimits implements RateLimits {
    private final Map<String,long[]> windows=new HashMap<>();
    public synchronized void check(Identity identity,JsonNode policy,String scope) {
        int seconds=scope.equals("work")?policy.path("limits").path("window_seconds").asInt():60;
        int maximum=scope.equals("work")?policy.path("limits").path("requests_per_window").asInt():100;
        long window=System.nanoTime()/(seconds*1_000_000_000L);
        String key=identity.tenant()+"/"+identity.application()+"/"+scope;
        long[] counter=windows.computeIfAbsent(key,k->new long[]{window,0});
        if(counter[0]!=window) {counter[0]=window;counter[1]=0;}
        if(counter[1]>=maximum) throw new ApiFailure(429,"RATE_LIMITED");counter[1]++;
    }
}

/** Redis TIME and atomic Lua prevent split INCR/expiry and gateway clock disagreement. */
final class RedisRateLimits implements RateLimits {
    private static final String QUOTA="""
        local t=redis.call('TIME')
        local window=math.floor(tonumber(t[1])/tonumber(ARGV[1]))
        local key=KEYS[1]..':'..window
        local count=tonumber(redis.call('GET',key) or '0')
        if count>=tonumber(ARGV[2]) then return 0 end
        redis.call('INCR',key)
        redis.call('EXPIREAT',key,(window+1)*tonumber(ARGV[1])+1)
        return 1
        """;
    private static final String LEASE="""
        local t=redis.call('TIME')
        local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
        redis.call('ZREMRANGEBYSCORE',KEYS[1],'-inf',now)
        if redis.call('ZCARD',KEYS[1])>=tonumber(ARGV[1]) then return 0 end
        redis.call('ZADD',KEYS[1],now+tonumber(ARGV[2]),ARGV[3])
        local latest=redis.call('ZREVRANGE',KEYS[1],0,0,'WITHSCORES')
        redis.call('PEXPIREAT',KEYS[1],tonumber(latest[2])+1000)
        return 1
        """;
    private final io.lettuce.core.resource.DefaultClientResources resources;
    private final RedisClient client;
    private final StatefulRedisConnection<String,String> connection;
    RedisRateLimits(String host,int port,String password) {
        if(password.length()<32 || host.isBlank() || port<1 || port>65535) throw new IllegalArgumentException("Invalid Redis configuration.");
        resources=io.lettuce.core.resource.DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2)
                .reconnectDelay(io.lettuce.core.resource.Delay.constant(Duration.ofMillis(500))).build();
        client=RedisClient.create(resources,RedisURI.Builder.redis(host,port).withPassword(password.toCharArray()).withTimeout(Duration.ofSeconds(2)).build());
        client.setOptions(ClientOptions.builder().autoReconnect(true).disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(2)).build()).requestQueueSize(64)
                .replayFilter(command->true).build());
        try {connection=client.connect();connection.sync().ping();}
        catch(Exception ex) {client.shutdown();resources.shutdown();throw new IllegalStateException("Redis startup dependency unavailable.");}
    }
    private static String digest(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception ex){throw ApiFailure.unavailable();}
    }
    public void check(Identity identity,JsonNode policy,String scope) {
        int seconds=scope.equals("work")?policy.path("limits").path("window_seconds").asInt():60;
        int maximum=scope.equals("work")?policy.path("limits").path("requests_per_window").asInt():100;
        try {
            Long allowed=connection.sync().eval(QUOTA,ScriptOutputType.INTEGER,new String[]{"sentinel:quota:"+digest(identity.tenant()+"/"+identity.application()+"/"+scope)+":"+seconds},""+seconds,""+maximum);
            if(allowed==null) throw ApiFailure.unavailable();if(allowed!=1) throw new ApiFailure(429,"RATE_LIMITED");
        } catch(ApiFailure ex){throw ex;}catch(Exception ex){throw ApiFailure.unavailable();}
    }
    public AutoCloseable acquire(Identity identity,JsonNode policy,String kind) {
        String key="sentinel:leases:"+digest(identity.tenant()+"/"+kind),token=UUID.randomUUID().toString();
        int maximum=kind.equals("chat")?policy.path("limits").path("max_concurrent_provider_calls").asInt():4;
        long ttl=2L*(policy.path("limits").path("provider_timeout_ms").asLong()+3L*policy.path("limits").path("inspection_timeout_ms").asLong()+10000)+30000;
        try {
            Long allowed=connection.sync().eval(LEASE,ScriptOutputType.INTEGER,new String[]{key},""+maximum,""+ttl,token);
            if(allowed==null) throw ApiFailure.unavailable();if(allowed!=1) throw new ApiFailure(429,"RATE_LIMITED");
            return ()->{try{connection.sync().zrem(key,token);}catch(Exception ignored){/* Lease expires; no retry of external work. */}};
        } catch(ApiFailure ex){throw ex;}catch(Exception ex){throw ApiFailure.unavailable();}
    }
    public void ready(){try{connection.sync().ping();}catch(Exception ex){throw ApiFailure.unavailable();}}
    public void close(){try{connection.close();client.shutdown();}finally{resources.shutdown();}}
}
