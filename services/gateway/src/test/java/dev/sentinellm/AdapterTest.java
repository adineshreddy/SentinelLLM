package dev.sentinellm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Path;
import java.util.*;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class AdapterTest {
    @Test void navigator_adapter_selects_normalized_path_key_and_configured_model() {
        var settings=new Settings(Map.of(),"http://localhost/internal/v1/inspect","test","navigator","https://api.ai.it.ufl.edu/","synthetic-key",Path.of("unused"),"","");
        AtomicReference<URI> destination=new AtomicReference<>();AtomicReference<String> key=new AtomicReference<>();AtomicReference<String> model=new AtomicReference<>();
        HttpTransport recording=(uri,credential,body,timeout,limit,provider)->{destination.set(uri);key.set(credential);model.set(body.path("model").asText());assertEquals(524288,limit);assertTrue(provider);return Contracts.object();};
        new NavigatorProvider(settings,recording).complete(Contracts.object().put("model","user-selected-model"),1000);
        assertEquals("https://api.ai.it.ufl.edu/v1/chat/completions",destination.get().toString());assertEquals("synthetic-key",key.get());assertEquals("user-selected-model",model.get());
    }
    @Test void hosted_access_requires_opt_in_and_key_but_mock_does_not() {
        var env=new MockEnvironment().withProperty("SENTINEL_APPLICATION_KEYS_JSON","[{\"sha256\":\""+"a".repeat(64)+"\",\"tenant_id\":\"demo\",\"application_id\":\"test\",\"roles\":[\"support_agent\"],\"models\":[\"gpt-oss-120b\"]}]")
                .withProperty("SENTINEL_INSPECTION_KEY","synthetic-inspection-secret-for-tests-only");
        assertEquals("mock",Settings.load(env).provider());
        env.withProperty("SENTINEL_PROVIDER","navigator");assertThrows(IllegalStateException.class,()->Settings.load(env));
        env.withProperty("SENTINEL_HOSTED_ENABLED","true");assertThrows(IllegalStateException.class,()->Settings.load(env));
        env.withProperty("NAVIGATOR_API_KEY","synthetic-provider-secret");
        var settings=Settings.load(env);assertEquals("navigator",settings.provider());assertFalse(settings.toString().contains("synthetic-provider-secret"));
    }
    @ParameterizedTest @ValueSource(strings={"https://api.ai.it.ufl.edu","https://api.ai.it.ufl.edu/","https://api.ai.it.ufl.edu/v1","https://api.ai.it.ufl.edu/v1/"})
    void normalize_once(String input) { assertEquals("https://api.ai.it.ufl.edu/v1",NavigatorProvider.normalizeBase(input)); }
    @ParameterizedTest @ValueSource(strings={"http://api.ai.it.ufl.edu","https://evil.test/v1","https://key@api.ai.it.ufl.edu/v1","https://api.ai.it.ufl.edu/v1?token=x","https://api.ai.it.ufl.edu/v1/v1","https://api.ai.it.ufl.edu:444/v1"})
    void reject_untrusted_destinations(String input) { assertThrows(IllegalArgumentException.class,()->NavigatorProvider.normalizeBase(input)); }
    @Test void actual_http_payload_and_bearer_injection() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicReference<String> auth=new AtomicReference<>(),body=new AtomicReference<>();
        server.createContext("/v1/chat/completions",exchange->{auth.set(exchange.getRequestHeaders().getFirst("Authorization"));body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] b="{\"choices\":[]}".getBytes();exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,b.length);exchange.getResponseBody().write(b);exchange.close();});server.start();
        try {
            var result=new BoundedHttp().post(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/chat/completions"),"synthetic-provider-key",Contracts.object().put("model","configurable-model"),2000,524288,true);
            assertEquals("Bearer synthetic-provider-key",auth.get());assertTrue(body.get().contains("configurable-model"));assertTrue(result.has("choices"));
        } finally {server.stop(0);}
    }
    @Test void response_body_limit_and_sanitized_errors() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{byte[] b="secret-error-payload".repeat(100).getBytes();exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write(b);exchange.close();});server.start();
        try {
            var ex=assertThrows(ApiFailure.class,()->new BoundedHttp().post(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),"key",Contracts.object(),2000,32,true));
            assertEquals(502,ex.status);assertFalse(ex.getMessage().contains("secret-error-payload"));
        } finally {server.stop(0);}
    }
    @Test void slow_body_deadline_includes_body_delivery() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write('{');exchange.getResponseBody().flush();try{Thread.sleep(1000);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}exchange.close();});server.start();
        try {
            var ex=assertThrows(ApiFailure.class,()->new BoundedHttp().post(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),"key",Contracts.object(),150,524288,true));assertEquals(504,ex.status);
        } finally {server.stop(0);}
    }

    @Test void interrupt_cancels_wait_and_preserves_thread_interrupt() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var failure=new AtomicReference<ApiFailure>();var interrupted=new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/",exchange->{entered.countDown();try{release.await(3,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException ignored){}exchange.close();});server.start();
        Thread worker=new Thread(()->{try{new BoundedHttp().post(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),"synthetic",Contracts.object(),5000,1024,true);}catch(ApiFailure ex){failure.set(ex);interrupted.set(Thread.currentThread().isInterrupted());}});
        try{worker.start();assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS));worker.interrupt();worker.join(1000);assertFalse(worker.isAlive());assertEquals(503,failure.get().status);assertTrue(interrupted.get());}
        finally{release.countDown();server.stop(0);worker.join(1000);}
    }
    @Test void mock_latency_obeys_provider_deadline(){
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var delayed=new DelayedMockProvider((body,timeout)->{calls.incrementAndGet();return Contracts.object();},100);
        var failure=assertThrows(ApiFailure.class,()->delayed.complete(Contracts.object(),10));assertEquals(504,failure.status);assertEquals(0,calls.get());
    }
}
