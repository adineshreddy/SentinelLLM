package dev.sentinellm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.io.ByteArrayInputStream;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpBoundaryTest {
    static final String KEY="synthetic-gateway-credential-for-http-tests-only";
    static final AtomicInteger INSPECTIONS=new AtomicInteger();
    static final AtomicBoolean FAILED=new AtomicBoolean();
    static final HttpServer INSPECTOR;
    static final Path AUDIT;
    static {
        try {
            AUDIT=Files.createTempDirectory("sentinel-http-test-").resolve("audit.jsonl");
            INSPECTOR=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            INSPECTOR.createContext("/internal/v1/inspect",exchange->{
                INSPECTIONS.incrementAndGet();
                var request=Contracts.JSON.readTree(exchange.getRequestBody());
                var report=Contracts.object().put("operation_id",request.path("operation_id").asText()).put("status",FAILED.get()?"failed":"complete").put("truncated",false);
                var versions=report.putObject("detector_versions");for(String cat:List.of("prompt_injection","jailbreak","secret","pii")) versions.put(cat,"rules-v1");report.putArray("findings");
                byte[] body=Contracts.JSON.writeValueAsBytes(report);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
            });INSPECTOR.start();
        } catch(Exception ex){throw new ExceptionInInitializerError(ex);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("SENTINEL_APPLICATION_KEYS_JSON",()->{
            try {String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(KEY.getBytes(StandardCharsets.UTF_8)));
                return "[{\"sha256\":\""+digest+"\",\"tenant_id\":\"demo\",\"application_id\":\"http-test\",\"roles\":[\"support_agent\"],\"models\":[\"gpt-oss-120b\"]}]";
            } catch(Exception ex){throw new IllegalStateException();}
        });
        r.add("SENTINEL_METRICS_KEY",()->"synthetic_metrics_key_for_http_tests_12345");
        r.add("SENTINEL_INSPECTION_KEY",()->"synthetic-inspection-credential-for-http-tests");
        r.add("SENTINEL_INSPECTION_URL",()->"http://127.0.0.1:"+INSPECTOR.getAddress().getPort()+"/internal/v1/inspect");
        r.add("SENTINEL_AUDIT_PATH",AUDIT::toString);
    }
    @LocalServerPort int port;
    final HttpClient client=HttpClient.newHttpClient();
    HttpResponse<String> post(byte[] body,boolean auth,boolean chunked) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v1/chat/completions")).header("Content-Type","application/json");
        if(auth)request.header("Authorization","Bearer "+KEY);
        return client.send(request.POST(chunked?HttpRequest.BodyPublishers.ofInputStream(()->new ByteArrayInputStream(body)):HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    byte[] normal() { return "{\"model\":\"gpt-oss-120b\",\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}".getBytes(StandardCharsets.UTF_8); }
    @Test void auth_before_parsing_and_no_inspection() throws Exception {
        int before=INSPECTIONS.get();var response=post("malformed-secret".getBytes(),false,false);
        assertEquals(401,response.statusCode());assertFalse(response.body().contains("malformed-secret"));assertEquals(before,INSPECTIONS.get());assertTrue(response.headers().firstValue("X-Sentinel-Operation-ID").isPresent());
    }
    @Test void real_http_success_matches_response_contract() throws Exception {
        var response=post(normal(),true,false);assertEquals(200,response.statusCode());
        new Contracts().validate("chat-response",Contracts.JSON.readTree(response.body()),true);
        assertEquals("mock",Contracts.JSON.readTree(response.body()).path("sentinel").path("provider_mode").asText());
    }
    @Test void role_spoof_duplicate_fields_and_invalid_utf8_rejected() throws Exception {
        int before=INSPECTIONS.get();
        for(byte[] body:List.of("{\"role\":\"operator\",\"secret\":\"do-not-echo\"}".getBytes(),"{\"model\":\"x\",\"model\":\"secret-model\"}".getBytes(),new byte[]{(byte)0xff})) {
            var response=post(body,true,false);assertEquals(400,response.statusCode());assertFalse(response.body().contains("do-not-echo"));assertFalse(response.body().contains("secret-model"));
        }assertEquals(before,INSPECTIONS.get());
    }
    @Test void oversized_known_length_and_chunked_bodies_rejected() throws Exception {
        int before=INSPECTIONS.get();
        assertEquals(413,post(" ".repeat(262145).getBytes(),true,false).statusCode());
        assertEquals(413,post(" ".repeat(262145).getBytes(),true,true).statusCode());assertEquals(before,INSPECTIONS.get());
    }
    @Test void partial_inspection_blocks_http_release() throws Exception {
        FAILED.set(true);try{assertEquals(503,post(normal(),true,false).statusCode());}finally{FAILED.set(false);}
    }
    @Test void scrape_requires_monitoring_key_and_key_cannot_execute_work() throws Exception {
        var url=URI.create("http://127.0.0.1:"+port+"/internal/metrics");
        assertEquals(401,client.send(HttpRequest.newBuilder(url).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(401,client.send(HttpRequest.newBuilder(url).header("Authorization","Bearer "+KEY).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        var scrape=client.send(HttpRequest.newBuilder(url).header("Authorization","Bearer synthetic_metrics_key_for_http_tests_12345").GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,scrape.statusCode());assertTrue(scrape.body().contains("jvm_memory_used_bytes"));assertFalse(scrape.body().contains(KEY));
        var work=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/v1/chat/completions")).header("Authorization","Bearer synthetic_metrics_key_for_http_tests_12345").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(normal())).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(401,work.statusCode());
    }
    @AfterAll static void cleanup() throws Exception {INSPECTOR.stop(0);Files.deleteIfExists(AUDIT);Files.deleteIfExists(AUDIT.getParent());}
}
