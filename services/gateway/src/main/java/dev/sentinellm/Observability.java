package dev.sentinellm;

import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.*;
import io.micrometer.core.instrument.binder.system.*;
import io.micrometer.prometheusmetrics.*;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Deliberately finite labels. Never register payload, identity, URI, exception text or detector version. */
final class Observability implements AutoCloseable {
    final PrometheusMeterRegistry registry=new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final java.util.Map<String,java.util.concurrent.atomic.AtomicInteger> active=new java.util.HashMap<>();
    private final String credential;
    private final Semaphore admission;
    private final int capacity;
    Observability(Environment env) {
        credential=env.getProperty("SENTINEL_METRICS_KEY","");
        if(!credential.isEmpty() && (credential.length()<32 || !credential.matches("[A-Za-z0-9_-]+"))) throw new IllegalStateException("Invalid metrics credential.");
        capacity=env.getProperty("SENTINEL_HTTP_CAPACITY",Integer.class,32);
        if(capacity<1||capacity>256)throw new IllegalStateException("Invalid request capacity.");
        admission=new Semaphore(capacity);
        registry.gauge("sentinel.http.active",this,m->m.capacity-m.admission.availablePermits());
        registry.gauge("sentinel.http.capacity",capacity);
        new JvmMemoryMetrics().bindTo(registry);new JvmThreadMetrics().bindTo(registry);
        new ClassLoaderMetrics().bindTo(registry);new ProcessorMetrics().bindTo(registry);
        new UptimeMetrics().bindTo(registry);
        for(String kind:Set.of("inspection","provider","tool","audit","state","redis")){
            for(String result:Set.of("success","failure","rejected"))timer("sentinel.dependency.duration","dependency",kind,"result",result);
            registry.counter("sentinel.dependency.calls","dependency",kind);
            var count=new java.util.concurrent.atomic.AtomicInteger();active.put(kind,count);
            registry.gauge("sentinel.dependency.active",java.util.List.of(io.micrometer.core.instrument.Tag.of("dependency",kind)),count,java.util.concurrent.atomic.AtomicInteger::get);
        }
    }
    boolean authorized(String header) {
        return !credential.isEmpty() && header!=null && header.length()<=1024 &&
            MessageDigest.isEqual(("Bearer "+credential).getBytes(StandardCharsets.UTF_8),header.getBytes(StandardCharsets.UTF_8));
    }
    boolean admit() {
        boolean allowed=admission.tryAcquire();
        if(!allowed)registry.counter("sentinel.admission.rejections").increment();
        return allowed;
    }
    void release(){admission.release();}
    static String route(String uri) {
        return switch(uri){
            case "/v1/chat/completions"->"chat";case "/api/v1/inspect"->"preview";
            case "/api/v1/tools"->"discovery";case "/api/v1/tools/execute"->"tool";
            case "/health/live","/health/ready"->"health";case "/internal/metrics"->"metrics";
            case "/api/v1/management/config","/api/v1/management/config/history","/api/v1/management/events"->"management";
            default->uri.startsWith("/api/v1/management/operations/")?"management":"other";
        };
    }
    void http(String route,int status,long started) {
        String result=status>=500?"server_error":status==429?"limited":status==403?"denied":status>=400?"client_error":"success";
        timer("sentinel.http.duration","route",route,"result",result).record(System.nanoTime()-started,TimeUnit.NANOSECONDS);
    }
    private Timer timer(String name,String...tags) {
        return Timer.builder(name).tags(tags).publishPercentileHistogram()
            .serviceLevelObjectives(Duration.ofMillis(5),Duration.ofMillis(25),Duration.ofMillis(100),Duration.ofMillis(500),Duration.ofSeconds(3),Duration.ofSeconds(60))
            .minimumExpectedValue(Duration.ofMillis(1)).maximumExpectedValue(Duration.ofSeconds(90)).register(registry);
    }
    <T> T dependency(String kind,Supplier<T> work) {
        long start=System.nanoTime();String result="success";
        var count=active.get(kind);if(count==null)throw new IllegalArgumentException("Unregistered dependency metric.");
        count.incrementAndGet();registry.counter("sentinel.dependency.calls","dependency",kind).increment();
        try{return work.get();}catch(RuntimeException ex){result=ex instanceof ApiFailure a&&a.status<500?"rejected":"failure";throw ex;}
        finally{count.decrementAndGet();timer("sentinel.dependency.duration","dependency",kind,"result",result).record(System.nanoTime()-start,TimeUnit.NANOSECONDS);}
    }
    Inspector inspector(Inspector wrapped){return (id,segments,timeout)->dependency("inspection",()->wrapped.inspect(id,segments,timeout));}
    Provider provider(Provider wrapped){return (body,timeout)->dependency("provider",()->wrapped.complete(body,timeout));}
    ToolExecutor tools(ToolExecutor wrapped){return (id,args,timeout)->dependency("tool",()->wrapped.execute(id,args,timeout));}
    Audit audit(Audit wrapped){return new Audit(){
        public void write(JsonNode decision,Identity identity,String provider,String outcome,long elapsed){
            dependency("audit",()->{wrapped.write(decision,identity,provider,outcome,elapsed);return null;});
            String stage=bounded(decision.path("stage").asText(),Set.of("input","output","preview","tool_input","tool_output"));
            String action=bounded(decision.path("action").asText(),Set.of("allow","deny","redact"));
            registry.counter("sentinel.decisions","stage",stage,"action",action).increment();
        }
        public void beforeDispatch(String op,Identity identity,String kind,String tool){dependency("audit",()->{wrapped.beforeDispatch(op,identity,kind,tool);return null;});}
        public void afterReturn(String op,Identity identity){dependency("audit",()->{wrapped.afterReturn(op,identity);return null;});}
    };}
    RateLimits rates(RateLimits wrapped){return new RateLimits(){
        public void check(Identity id,JsonNode policy,String scope){dependency("redis",()->{wrapped.check(id,policy,scope);return null;});}
        public AutoCloseable acquire(Identity id,JsonNode policy,String kind){return dependency("redis",()->wrapped.acquire(id,policy,kind));}
        public void ready(){dependency("redis",()->{wrapped.ready();return null;});}
    };}
    DurableState state(DurableState wrapped){if(wrapped==null)return null;return new DurableState(){
        public Snapshot snapshot(Identity id){return dependency("state",()->wrapped.snapshot(id));}
        public void start(String op,Identity id,String kind,Snapshot snap){dependency("state",()->{wrapped.start(op,id,kind,snap);return null;});}
        public void finish(String op,Identity id,int status,String code){dependency("state",()->{wrapped.finish(op,id,status,code);return null;});}
        public com.fasterxml.jackson.databind.node.ObjectNode configuration(Identity id){return dependency("state",()->wrapped.configuration(id));}
        public com.fasterxml.jackson.databind.node.ObjectNode update(Identity id,JsonNode body){return dependency("state",()->wrapped.update(id,body));}
        public com.fasterxml.jackson.databind.node.ObjectNode history(Identity id,long before,int limit){return dependency("state",()->wrapped.history(id,before,limit));}
        public com.fasterxml.jackson.databind.node.ObjectNode events(Identity id,long before,int limit,String action,String stage,String op){return dependency("state",()->wrapped.events(id,before,limit,action,stage,op));}
        public com.fasterxml.jackson.databind.node.ObjectNode trace(Identity id,String op){return dependency("state",()->wrapped.trace(id,op));}
        public void close(){} // RuntimeState owns and closes the underlying store.
    };}
    private static String bounded(String value,Set<String> valid){return valid.contains(value)?value:"other";}
    public void close(){registry.close();}
}

@RestController
final class MetricsController {
    private final Observability metrics;
    MetricsController(Observability metrics){this.metrics=metrics;}
    @GetMapping(value="/internal/metrics",produces="text/plain; version=0.0.4; charset=utf-8")
    String scrape(){return metrics.registry.scrape();}
}
