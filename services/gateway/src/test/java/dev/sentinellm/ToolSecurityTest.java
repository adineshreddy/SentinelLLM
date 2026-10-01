package dev.sentinellm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ToolSecurityTest {
    @TempDir Path temp;
    final GatewayEngineTest fixture=new GatewayEngineTest();
    final AtomicInteger executions=new AtomicInteger();
    @BeforeEach void setup(){fixture.temp=temp;}
    ObjectNode tool(String id,JsonNode args){var n=Contracts.object().put("tool_id",id);n.set("arguments",args);return n;}
    ObjectNode query(String value){return Contracts.object().put("query",value);}
    GatewayEngine engine(ToolExecutor executor,Provider provider,Audit audit,ToolRegistry registry){return new GatewayEngine(fixture.settings(),fixture.contracts,(id,s,t)->fixture.report(id,s),provider,audit,registry,(id,a,t)->{executions.incrementAndGet();return executor.execute(id,a,t);});}
    GatewayEngine engine(ToolExecutor executor){return engine(executor,new MockProvider("safe"),(d,i,p,o,t)->fixture.events.add(d),new ToolRegistry(fixture.contracts));}
    GatewayEngine engine(){return engine(new DemoTools());}
    @Test void valid_call_requires_both_inspections_and_returns_registered_result(){
        var result=engine().execute(tool("kb.search",query("password")),fixture.agent,fixture.op());
        assertEquals(1,executions.get());assertEquals(2,result.path("decision_ids").size());assertEquals(2,fixture.events.size());
        assertEquals("tool_input",fixture.events.get(0).path("stage").asText());assertEquals("tool_output",fixture.events.get(1).path("stage").asText());
    }
    @Test void denied_tool_role_and_resource_never_execute(){
        GatewayEngine e=engine();
        for(String id:List.of("ticket.delete","unknown")) assertEquals(403,assertThrows(ApiFailure.class,()->e.execute(tool(id,query("safe")),fixture.agent,fixture.op())).status);
        var viewer=new Identity("demo","viewer",Set.of("viewer"),Set.of());
        assertEquals(403,assertThrows(ApiFailure.class,()->e.execute(tool("kb.search",query("safe")),viewer,fixture.op())).status);
        assertEquals(400,assertThrows(ApiFailure.class,()->e.execute(tool("ticket.get",Contracts.object().put("ticket_id","PRIVATE-42")),fixture.agent,fixture.op())).status);
        assertEquals(0,executions.get());assertEquals(0,e.tools(viewer).path("tools").size());
    }
    @Test void extra_arguments_and_identity_spoofing_never_execute(){
        GatewayEngine e=engine();
        assertEquals(400,assertThrows(ApiFailure.class,()->e.execute(tool("kb.search",query("safe").put("url","https://evil.test")),fixture.agent,fixture.op())).status);
        assertEquals(400,assertThrows(ApiFailure.class,()->e.execute(tool("kb.search",query("safe")).put("roles","support_agent"),fixture.agent,fixture.op())).status);
        assertEquals(0,executions.get());
    }
    @Test void pii_and_injection_arguments_are_blocked_instead_of_rewritten(){
        GatewayEngine e=engine();for(String value:List.of("alice@example.test","attack-fixture")) assertEquals(403,assertThrows(ApiFailure.class,()->e.execute(tool("kb.search",query(value)),fixture.agent,fixture.op())).status);
        assertEquals(0,executions.get());
    }
    @Test void inspector_and_audit_failure_prevent_execution(){
        var registry=new ToolRegistry(fixture.contracts);
        var e=engine(new DemoTools(),new MockProvider("safe"),(d,i,p,o,t)->{throw ApiFailure.unavailable();},registry);
        assertEquals(503,assertThrows(ApiFailure.class,()->e.execute(tool("kb.search",query("safe")),fixture.agent,fixture.op())).status);assertEquals(0,executions.get());
        var broken=new GatewayEngine(fixture.settings(),fixture.contracts,(id,s,t)->{throw ApiFailure.unavailable();},new MockProvider("safe"),(d,i,p,o,t)->{},registry,(id,a,t)->{executions.incrementAndGet();return Contracts.object();});
        assertEquals(503,assertThrows(ApiFailure.class,()->broken.execute(tool("kb.search",query("safe")),fixture.agent,fixture.op())).status);assertEquals(0,executions.get());
    }
    JsonNode result(String text){var result=Contracts.object();result.putArray("documents").addObject().put("id","kb-001").put("text",text);return result;}
    @Test void result_injection_is_blocked_and_pii_is_redacted(){
        var bad=engine((id,a,t)->result("attack-fixture"));assertEquals(403,assertThrows(ApiFailure.class,()->bad.execute(tool("kb.search",query("safe")),fixture.agent,fixture.op())).status);
        var safe=engine((id,a,t)->result("👋 alice@example.test"));var response=safe.execute(tool("kb.search",query("safe")),fixture.agent,fixture.op());
        assertEquals("👋 [REDACTED]",response.path("result").path("documents").get(0).path("text").asText());
    }
    @Test void malformed_result_never_released(){assertEquals(502,assertThrows(ApiFailure.class,()->engine((id,a,t)->Contracts.object().put("raw","unsafe")).execute(tool("kb.search",query("safe")),fixture.agent,fixture.op())).status);}
    @Test void uncertain_execution_is_not_retried_and_is_audited(){
        List<String> outcomes=new ArrayList<>();var e=engine((id,a,t)->{throw new ApiFailure(503,"OUTCOME_UNCERTAIN");},new MockProvider("safe"),(d,i,p,o,t)->outcomes.add(o),new ToolRegistry(fixture.contracts));
        assertEquals("OUTCOME_UNCERTAIN",assertThrows(ApiFailure.class,()->e.execute(tool("kb.search",query("safe")),fixture.agent,fixture.op())).code);
        assertEquals(1,executions.get());assertEquals(List.of("approved","uncertain"),outcomes);
    }
    @Test void registry_rejects_remote_reference_and_duplicate_id(){
        ObjectNode source=(ObjectNode)fixture.contracts.resource("policies/tool-registry-v1.json");
        ((ObjectNode)source.path("tools").get(0).path("input_schema")).put("$ref","https://evil.invalid/schema.json");
        assertThrows(IllegalStateException.class,()->new ToolRegistry(fixture.contracts,source));
        var duplicate=fixture.contracts.resource("policies/tool-registry-v1.json");((ArrayNode)duplicate.path("tools")).add(duplicate.path("tools").get(0).deepCopy());
        assertThrows(IllegalStateException.class,()->new ToolRegistry(fixture.contracts,duplicate));
    }
    ObjectNode structuredRequest(){var n=fixture.request("help");n.putObject("sentinel").put("output_schema_id","support-answer-v1");return n;}
    GatewayEngine chat(String output){return engine(new DemoTools(),new MockProvider(output),(d,i,p,o,t)->fixture.events.add(d),new ToolRegistry(fixture.contracts));}
    @Test void valid_structured_output_is_inspected_without_json_escape_bypass(){
        var response=chat("{\"answer\":\"alice\\u0040example.test\",\"source_ids\":[\"kb-001\"]}").chat(structuredRequest(),fixture.agent,fixture.op());
        assertEquals("{\"answer\":\"[REDACTED]\",\"source_ids\":[\"kb-001\"]}",response.path("choices").get(0).path("message").path("content").asText());
    }
    @Test void invalid_json_shape_and_unknown_schema_never_released(){
        for(String output:List.of("not JSON","{\"answer\":\"one\",\"answer\":\"two\",\"source_ids\":[]}","{\"answer\":7,\"source_ids\":[]}")) assertTrue(assertThrows(ApiFailure.class,()->chat(output).chat(structuredRequest(),fixture.agent,fixture.op())).status>=500);
        assertEquals(403,assertThrows(ApiFailure.class,()->chat("{}").chat(fixture.request("help").set("sentinel",Contracts.object().put("output_schema_id","arbitrary")),fixture.agent,fixture.op())).status);
    }
    JsonNode proposal(String name,String args){var n=Contracts.object();var choice=n.putArray("choices").addObject().put("finish_reason","tool_calls");var message=choice.putObject("message").put("role","assistant").putNull("content");message.putArray("tool_calls").addObject().put("id","call_1").put("type","function").putObject("function").put("name",name.replace('.','_')).put("arguments",args);return n;}
    ObjectNode proposalRequest(){var n=fixture.request("help");n.putObject("sentinel").putArray("tool_ids").add("kb.search");return n;}
    GatewayEngine proposed(String name,String args){return engine(new DemoTools(),(a,t)->proposal(name,args),(d,i,p,o,t)->fixture.events.add(d),new ToolRegistry(fixture.contracts));}
    @Test void approved_function_proposal_is_validated_but_never_executed(){
        var response=proposed("kb.search","{\"query\":\"help\"}").chat(proposalRequest(),fixture.agent,fixture.op());
        assertEquals("tool_calls",response.path("choices").get(0).path("finish_reason").asText());assertEquals(0,executions.get());
    }
    @Test void invalid_and_unrequested_function_proposals_are_denied(){
        assertEquals(403,assertThrows(ApiFailure.class,()->proposed("ticket.delete","{}").chat(proposalRequest(),fixture.agent,fixture.op())).status);
        assertEquals(403,assertThrows(ApiFailure.class,()->proposed("ticket.get","{\"ticket_id\":\"DEMO-1\"}").chat(proposalRequest(),fixture.agent,fixture.op())).status);
        assertEquals(502,assertThrows(ApiFailure.class,()->proposed("kb.search","{\"query\":7}").chat(proposalRequest(),fixture.agent,fixture.op())).status);
        assertEquals(403,assertThrows(ApiFailure.class,()->proposed("kb.search","{\"query\":\"alice\\u0040example.test\"}").chat(proposalRequest(),fixture.agent,fixture.op())).status);
        assertEquals(0,executions.get());
    }
    @Test void mcp_bridge_correlation_failure_never_returns_result(){
        var bridge=new McpTools("http://localhost/internal/v1/tools/execute","synthetic-service-key-at-least-32-characters",(u,k,b,t,l,p)->Contracts.object().put("tool_id","different").set("result",Contracts.object()));
        assertEquals("OUTCOME_UNCERTAIN",assertThrows(ApiFailure.class,()->bridge.execute("kb.search",query("safe"),100)).code);
    }
    @Test void redacted_tool_result_is_revalidated_before_completion() {
        var source=fixture.contracts.resource("policies/tool-registry-v1.json");
        ((ObjectNode)source.path("tools").get(0).path("output_schema").path("properties").path("documents").path("items").path("properties").path("text")).put("minLength",18);
        List<String> outcomes=new ArrayList<>();
        var e=engine((id,a,t)->result("alice@example.test"),new MockProvider("safe"),(d,i,p,o,t)->outcomes.add(o),new ToolRegistry(fixture.contracts,source));
        assertEquals(502,assertThrows(ApiFailure.class,()->e.execute(tool("kb.search",query("safe")),fixture.agent,fixture.op())).status);
        assertFalse(outcomes.contains("completed"));
    }
}
