package dev.sentinellm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class GatewayEngineTest {
    @TempDir Path temp;
    final Contracts contracts=new Contracts();
    final Identity agent=new Identity("demo","test-app",Set.of("support_agent"),Set.of("gpt-oss-120b"));
    final AtomicInteger calls=new AtomicInteger();
    final AtomicReference<ObjectNode> forwarded=new AtomicReference<>();
    final List<JsonNode> events=new ArrayList<>();
    String op() { return UUID.randomUUID().toString(); }
    Settings settings() { return new Settings(Map.of("test",agent),"http://localhost/internal/v1/inspect","test-key","mock","https://api.ai.it.ufl.edu/v1","",temp.resolve("audit.jsonl"),"","safe response"); }
    ObjectNode request(String text) {
        ObjectNode n=Contracts.object().put("model","gpt-oss-120b");n.putArray("messages").addObject().put("role","user").put("content",text);return n;
    }
    ObjectNode report(String operation,List<Segment> segments) {
        ObjectNode report=Contracts.object().put("operation_id",operation).put("status","complete").put("truncated",false);
        var versions=report.putObject("detector_versions");
        for (String cat:List.of("prompt_injection","jailbreak","secret","pii")) versions.put(cat,"rules-v1");
        var findings=report.putArray("findings");
        for (Segment s:segments) {
            if (s.text().contains("attack-fixture")) finding(findings,s,"PI.OVERRIDE","prompt_injection",0,6);
            int start=s.text().indexOf("alice@example.test");
            if (start>=0) finding(findings,s,"PII.EMAIL","pii",s.text().substring(0,start).getBytes(StandardCharsets.UTF_8).length,s.text().substring(0,start+18).getBytes(StandardCharsets.UTF_8).length);
        }
        return report;
    }
    void finding(ArrayNode findings,Segment s,String rule,String category,int start,int end) {
        findings.addObject().put("rule_id",rule).put("category",category).put("severity","high").put("segment_id",s.id()).put("start_byte",start).put("end_byte",end).put("detector_version","rules-v1");
    }
    GatewayEngine engine(String output,Inspector inspector,Audit audit) {
        Provider provider=(body,timeout)->{calls.incrementAndGet();forwarded.set(body.deepCopy());return new MockProvider(output).complete(body,timeout);};
        return new GatewayEngine(settings(),contracts,inspector,provider,audit);
    }
    GatewayEngine engine(String output) { return engine(output,(id,segs,timeout)->report(id,segs),(d,i,p,o,t)->events.add(d)); }
    @Test void benign_chat_and_model_configuration() {
        var result=engine("approved").chat(request("normal"),agent,op());
        assertEquals(1,calls.get());assertEquals("approved",result.path("choices").get(0).path("message").path("content").asText());
        assertEquals(256,forwarded.get().path("max_tokens").asInt());assertEquals(3,events.size());
        assertThrows(ApiFailure.class,()->engine("ok").chat(request("normal").put("model","unregistered"),agent,op()));
        assertEquals(1,calls.get());
    }
    @Test void denied_prompt_does_not_forward() {
        ApiFailure ex=assertThrows(ApiFailure.class,()->engine("ok").chat(request("attack-fixture"),agent,op()));
        assertEquals(403,ex.status);assertEquals(0,calls.get());assertEquals("deny",events.getFirst().path("action").asText());
    }
    ObjectNode mlReport(String operation,List<Segment> segments) {
        ObjectNode result=report(operation,segments);
        ((ObjectNode)result.path("detector_versions")).put("prompt_injection","rules-v1+tfidf-pi-v1");
        ArrayNode findings=(ArrayNode)result.path("findings");
        for (Segment segment:segments) if (segment.text().contains("ml-only-fixture")) {
            findings.addObject().put("rule_id","ML.PROMPT_INJECTION").put("category","prompt_injection")
                    .put("severity","high").put("segment_id",segment.id()).put("start_byte",0)
                    .put("end_byte",segment.text().getBytes(StandardCharsets.UTF_8).length)
                    .put("detector_version","rules-v1+tfidf-pi-v1").put("score",0.91);
        }
        return result;
    }
    @Test void scored_ml_finding_blocks_before_model_dispatch() {
        var e=engine("approved",(id,segs,t)->mlReport(id,segs),(d,i,p,o,t)->events.add(d));
        assertEquals(403,assertThrows(ApiFailure.class,()->e.chat(request("👋 ml-only-fixture"),agent,op())).status);
        assertEquals(0,calls.get());
        assertEquals("ML.PROMPT_INJECTION",events.getFirst().path("findings").get(0).path("rule_id").asText());
        assertEquals(0.91,events.getFirst().path("findings").get(0).path("score").asDouble());
    }
    @Test void scored_ml_output_is_blocked_before_release() {
        var e=engine("ml-only-fixture",(id,segs,t)->mlReport(id,segs),(d,i,p,o,t)->events.add(d));
        assertEquals(403,assertThrows(ApiFailure.class,()->e.chat(request("normal"),agent,op())).status);
        assertEquals(1,calls.get());
    }
    @Test void malformed_ml_score_fails_closed() {
        var e=engine("approved",(id,segs,t)->{
            var result=mlReport(id,segs);
            ((ObjectNode)result.path("findings").get(0)).put("score",1.1);
            return result;
        },(d,i,p,o,t)->{});
        assertEquals(503,assertThrows(ApiFailure.class,()->e.chat(request("ml-only-fixture"),agent,op())).status);
        assertEquals(0,calls.get());
    }
    @Test void poisoned_context_does_not_forward() {
        var n=request("normal");n.putObject("sentinel").putArray("context").addObject().put("id","doc1").put("source_id","kb").put("text","attack-fixture");
        assertEquals(403,assertThrows(ApiFailure.class,()->engine("ok").chat(n,agent,op())).status);assertEquals(0,calls.get());
    }
    @Test void unicode_redaction_applies_before_provider_and_output_release() {
        var result=engine("Contact alice@example.test").chat(request("👋 café: alice@example.test"),agent,op());
        assertEquals("👋 café: [REDACTED]",forwarded.get().path("messages").get(0).path("content").asText());
        assertEquals("Contact [REDACTED]",result.path("choices").get(0).path("message").path("content").asText());
        assertEquals("redact",result.path("sentinel").path("input_action").asText());
    }
    @Test void context_is_untrusted_sanitized_user_content_and_extension_not_forwarded() {
        var n=request("normal");n.putObject("sentinel").putArray("context").addObject().put("id","doc1").put("source_id","kb").put("text","alice@example.test");
        engine("ok").chat(n,agent,op());
        assertFalse(forwarded.get().has("sentinel"));
        assertEquals("user",forwarded.get().path("messages").get(1).path("role").asText());
        assertFalse(forwarded.get().toString().contains("alice@example.test"));
    }
    @Test void blocked_model_output_never_released() {
        ApiFailure ex=assertThrows(ApiFailure.class,()->engine("attack-fixture").chat(request("normal"),agent,op()));
        assertEquals(403,ex.status);assertEquals(1,calls.get());assertFalse(ex.getMessage().contains("attack-fixture"));
    }
    @Test void inspector_failure_prevents_execution() {
        GatewayEngine e=engine("ok",(id,segs,t)->{throw ApiFailure.unavailable();},(d,i,p,o,t)->{});
        assertEquals(503,assertThrows(ApiFailure.class,()->e.chat(request("normal"),agent,op())).status);assertEquals(0,calls.get());
    }
    @ParameterizedTest @ValueSource(strings={"partial","truncated","wrong-id","wrong-span","unknown-rule","wrong-category"})
    void invalid_findings_fail_closed(String mode) {
        GatewayEngine e=engine("ok",(id,segs,t)->{
            var r=report(id,segs);
            switch(mode) {
                case "partial" -> r.put("status","failed");
                case "truncated" -> r.put("truncated",true);
                case "wrong-id" -> r.put("operation_id",op());
                case "wrong-span" -> ((ObjectNode)r.path("findings").get(0)).put("end_byte",999999);
                case "unknown-rule" -> ((ObjectNode)r.path("findings").get(0)).put("rule_id","UNKNOWN");
                case "wrong-category" -> ((ObjectNode)r.path("findings").get(0)).put("category","secret");
            }return r;
        },(d,i,p,o,t)->{});
        assertEquals(503,assertThrows(ApiFailure.class,()->e.chat(request("alice@example.test"),agent,op())).status);assertEquals(0,calls.get());
    }
    @Test void audit_failure_prevents_execution() {
        var e=engine("ok",(id,segs,t)->report(id,segs),new FileAudit(temp));
        assertEquals(503,assertThrows(ApiFailure.class,()->e.chat(request("normal"),agent,op())).status);assertEquals(0,calls.get());
    }
    @Test void output_audit_failure_prevents_release() {
        var e=engine("ok",(id,segs,t)->report(id,segs),(d,i,p,o,t)->{if(o.equals("completed"))throw ApiFailure.unavailable();});
        assertEquals(503,assertThrows(ApiFailure.class,()->e.chat(request("normal"),agent,op())).status);assertEquals(1,calls.get());
    }
    @Test void metadata_log_excludes_sensitive_content() throws Exception {
        var e=engine("ok",(id,segs,t)->report(id,segs),new FileAudit(temp.resolve("audit.jsonl")));
        e.chat(request("alice@example.test"),agent,op());
        String log=Files.readString(temp.resolve("audit.jsonl"));assertFalse(log.contains("alice@example.test"));assertFalse(log.contains("messages"));
        for (String line:log.lines().toList()) contracts.validate("audit-event",Contracts.JSON.readTree(line),true);
    }
    @Test void preview_denial_is_advisory_and_does_not_return_payload() {
        var n=Contracts.object();n.putArray("segments").addObject().put("id","s0").put("stage","rag").put("path","/text").put("text","attack-fixture");
        var result=engine("ok").preview(n,agent,op());
        assertEquals("deny",result.path("decision").path("action").asText());assertFalse(result.has("replacement_segments"));assertEquals(0,calls.get());
    }
    @Test void overlap_merges_and_invalid_unicode_spans_fail() {
        assertEquals("[REDACTED]",GatewayEngine.redact("abcdef",List.of(new int[]{0,4},new int[]{2,6})));
        assertEquals(503,assertThrows(ApiFailure.class,()->GatewayEngine.redact("👋 text",List.of(new int[]{1,4}))).status);
    }
    @Test void concurrent_rate_limit_is_atomic() throws Exception {
        var e=engine("ok",(id,segs,t)->report(id,segs),(d,i,p,o,t)->{});
        AtomicInteger approved=new AtomicInteger(),limited=new AtomicInteger();
        var n=Contracts.object();n.putArray("segments").addObject().put("id","s0").put("stage","prompt").put("path","/text").put("text","normal");
        try(var pool=Executors.newFixedThreadPool(4)) {
            List<Future<?>> pending=new ArrayList<>();
            for(int i=0;i<60;i++) pending.add(pool.submit(()->{try{e.preview(n,agent,op());approved.incrementAndGet();}catch(ApiFailure ex){if(ex.status==429)limited.incrementAndGet();else throw ex;}}));
            for(var f:pending)f.get();
        }
        assertEquals(30,approved.get());assertEquals(30,limited.get());
    }
    @Test void duplicate_json_non_finite_and_surrogates_rejected_without_echo() {
        for(String json:List.of("{\"model\":\"x\",\"model\":\"secret-value\"}","{\"x\":NaN}","{\"x\":\"\\uD800\"}","{} {}")) {
            var ex=assertThrows(ApiFailure.class,()->Contracts.parse(json.getBytes(StandardCharsets.UTF_8),false));assertEquals(400,ex.status);assertFalse(ex.getMessage().contains("secret-value"));
        }
    }
    @Test void capacity_rejects_excess_and_recovers() throws Exception {
        CountDownLatch entered=new CountDownLatch(2), release=new CountDownLatch(1);
        Provider slow=(body,t)->{entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}return new MockProvider("ok").complete(body,t);};
        var e=new GatewayEngine(settings(),contracts,(id,segs,t)->report(id,segs),slow,(d,i,p,o,t)->{});
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=pool.submit(()->e.chat(request("normal"),agent,op()));var second=pool.submit(()->e.chat(request("normal"),agent,op()));
            assertTrue(entered.await(2,TimeUnit.SECONDS));assertEquals(429,assertThrows(ApiFailure.class,()->e.chat(request("normal"),agent,op())).status);
            release.countDown();first.get();second.get();assertNotNull(e.chat(request("normal"),agent,op()));
        } finally {release.countDown();}
    }
    @Test void policy_snapshot_does_not_change_mid_lifecycle() throws Exception {
        Path file=temp.resolve("policy.json");var p=(ObjectNode)contracts.resource("policies/support-default-v1.json");Files.writeString(file,p.toString());
        Settings original=settings();var s=new Settings(original.identities(),original.inspectionUrl(),original.inspectionKey(),original.provider(),original.navigatorBase(),original.navigatorKey(),original.auditPath(),file.toString(),original.mockResponse());
        var e=new GatewayEngine(s,contracts,(id,segs,t)->report(id,segs),new MockProvider("ok"),(d,i,provider,o,t)->events.add(d));
        p.put("version",2);((ObjectNode)p.path("actions").path("prompt_injection")).put("prompt","allow");Files.writeString(file,p.toString());
        assertEquals(403,assertThrows(ApiFailure.class,()->e.chat(request("attack-fixture"),agent,op())).status);
        assertEquals(1,events.getFirst().path("policy_version").asInt());
    }
}
