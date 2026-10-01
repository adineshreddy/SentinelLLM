package dev.sentinellm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

interface Inspector { JsonNode inspect(String operationId, List<Segment> segments, int timeoutMs); }
interface Provider { JsonNode complete(ObjectNode approved, int timeoutMs); }
interface HttpTransport { JsonNode post(URI uri,String credential,JsonNode body,int timeoutMs,int limit,boolean provider); }

final class BoundedHttp implements HttpTransport {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    public JsonNode post(URI uri, String credential, JsonNode body, int timeoutMs, int limit, boolean provider) {
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            HttpRequest request=HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeoutMs))
                    .header("Authorization","Bearer "+credential).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(Contracts.JSON.writeValueAsBytes(body))).build();
            pending=client.sendAsync(request,info->new LimitedSubscriber(limit));
            HttpResponse<byte[]> response;
            try { response=pending.get(timeoutMs,TimeUnit.MILLISECONDS); }
            catch (TimeoutException ex) { pending.cancel(true); throw new ApiFailure(provider ? 504:503,provider ? "UPSTREAM_TIMEOUT":"DEPENDENCY_UNAVAILABLE"); }
            if (response.statusCode()!=200 || !response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT).startsWith("application/json"))
                throw new ApiFailure(provider ? 502:503,provider ? "UPSTREAM_FAILED":"DEPENDENCY_UNAVAILABLE");
            try { return Contracts.parse(response.body(),true); }
            catch (ApiFailure ex) { throw new ApiFailure(provider ? 502:503,provider ? "UPSTREAM_FAILED":"DEPENDENCY_UNAVAILABLE"); }
        } catch (ApiFailure ex) { throw ex; }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw ApiFailure.unavailable(); }
        catch (Exception ex) {
            Throwable cause=ex;
            while (cause.getCause()!=null) cause=cause.getCause();
            if (cause instanceof HttpTimeoutException) throw new ApiFailure(provider ? 504:503,provider ? "UPSTREAM_TIMEOUT":"DEPENDENCY_UNAVAILABLE");
            throw new ApiFailure(provider ? 502:503,provider ? "UPSTREAM_FAILED":"DEPENDENCY_UNAVAILABLE");
        } finally { if(pending!=null&&!pending.isDone())pending.cancel(true); }
    }
    /** Completes only after the entire bounded body: deadline includes slow body delivery. */
    static final class LimitedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;
        LimitedSubscriber(int limit) { this.limit=limit; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription s) { subscription=s; s.request(Long.MAX_VALUE); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer:buffers) {
                if ((long)bytes.size()+buffer.remaining()>limit) {
                    subscription.cancel(); result.completeExceptionally(new IllegalStateException("Response bound exceeded.")); return;
                }
                byte[] chunk=new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
        }
        public void onError(Throwable error) { result.completeExceptionally(new IllegalStateException("Response unavailable.")); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}

final class RemoteInspector implements Inspector {
    private final Settings settings;
    private final Contracts contracts;
    private final BoundedHttp http=new BoundedHttp();
    RemoteInspector(Settings settings, Contracts contracts) { this.settings=settings; this.contracts=contracts; }
    public JsonNode inspect(String operationId, List<Segment> segments, int timeoutMs) {
        ObjectNode request=Contracts.object().put("operation_id",operationId);
        var list=request.putArray("segments"); segments.forEach(s->list.add(s.json()));
        JsonNode result=http.post(URI.create(settings.inspectionUrl()),settings.inspectionKey(),request,timeoutMs,524288,false);
        contracts.validate("inspection-response",result,true);
        if (!result.path("operation_id").asText().equals(operationId) || !result.path("status").asText().equals("complete")
                || result.path("truncated").asBoolean()) throw ApiFailure.unavailable();
        return result;
    }
}

final class MockProvider implements Provider {
    private final String response;
    private final boolean agentFixtures;
    MockProvider(String response) { this(response,false); }
    MockProvider(String response,boolean agentFixtures) { this.response=response;this.agentFixtures=agentFixtures; }
    public JsonNode complete(ObjectNode approved, int timeoutMs) {
        ObjectNode result=Contracts.object();
        ObjectNode choice=result.putArray("choices").addObject().put("index",0).put("finish_reason","stop");
        ObjectNode message=choice.putObject("message").put("role","assistant");
        if (agentFixtures && approved.has("tools")) {
            String name=approved.path("tools").get(0).path("function").path("name").asText();
            choice.put("finish_reason","tool_calls");message.putNull("content");
            message.putArray("tool_calls").addObject().put("id","call_mock_1").put("type","function").putObject("function").put("name",name)
                    .put("arguments",name.equals("kb_search")?"{\"query\":\"password reset\"}":"{\"ticket_id\":\"DEMO-1\"}");
        } else if (agentFixtures && approved.has("response_format") && response.equals("SentinelLLM mock approved request.")) {
            message.put("content","{\"answer\":\"Reset your demo password from account settings.\",\"source_ids\":[\"kb-001\"]}");
        } else message.put("content",response);
        return result;
    }
}

final class NavigatorProvider implements Provider {
    private final URI endpoint;
    private final String key;
    private final HttpTransport http;
    NavigatorProvider(Settings settings) { this(settings,new BoundedHttp()); }
    NavigatorProvider(Settings settings,HttpTransport http) { endpoint=URI.create(normalizeBase(settings.navigatorBase())+"/chat/completions"); key=settings.navigatorKey(); this.http=http; }
    static String normalizeBase(String value) {
        try {
            URI uri=URI.create(value);
            String path=uri.getPath();
            if (!uri.getScheme().equals("https") || !"api.ai.it.ufl.edu".equalsIgnoreCase(uri.getHost())
                    || (uri.getPort()!=-1 && uri.getPort()!=443) || uri.getUserInfo()!=null || uri.getQuery()!=null
                    || uri.getFragment()!=null || !Set.of("","/","/v1","/v1/").contains(path)) throw new IllegalArgumentException();
            return "https://api.ai.it.ufl.edu/v1";
        } catch (Exception ex) { throw new IllegalArgumentException("Invalid registered provider URL."); }
    }
    public JsonNode complete(ObjectNode approved, int timeoutMs) { return http.post(endpoint,key,approved,timeoutMs,524288,true); }
}

/** Synthetic latency for local reliability exercises only; never applied to hosted generation. */
final class DelayedMockProvider implements Provider {
    private final Provider wrapped;private final int delay;
    DelayedMockProvider(Provider wrapped,int delay){this.wrapped=wrapped;this.delay=delay;}
    public JsonNode complete(ObjectNode body,int timeout){
        try{Thread.sleep(Math.min(delay,timeout));}
        catch(InterruptedException ex){Thread.currentThread().interrupt();throw ApiFailure.unavailable();}
        if(delay>=timeout&&delay>0)throw new ApiFailure(504,"UPSTREAM_TIMEOUT");
        return wrapped.complete(body,timeout);
    }
}
