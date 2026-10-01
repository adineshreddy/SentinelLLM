package dev.sentinellm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Semaphore;

record Segment(String id, String stage, String path, String text) {
    ObjectNode json() { return Contracts.object().put("id",id).put("stage",stage).put("path",path).put("text",text); }
}
record Evaluation(ObjectNode decision, List<Segment> sanitized) {}

final class GatewayEngine {
    private static final Map<String,String> RULES=Map.ofEntries(
            Map.entry("ML.PROMPT_INJECTION","prompt_injection"),Map.entry("PI.OVERRIDE","prompt_injection"),Map.entry("PI.EXFILTRATION","prompt_injection"),
            Map.entry("JB.UNRESTRICTED","jailbreak"),Map.entry("SECRET.PRIVATE_KEY","secret"),
            Map.entry("SECRET.OPENAI","secret"),Map.entry("SECRET.AWS","secret"),
            Map.entry("SECRET.GITHUB","secret"),Map.entry("SECRET.ASSIGNMENT","secret"),
            Map.entry("PII.EMAIL","pii"),Map.entry("PII.SSN","pii"));
    private final Settings settings;
    private final Contracts contracts;
    private final Inspector inspector;
    private final Provider provider;
    private final Audit audit;
    private final JsonNode initialPolicy;
    private final DurableState state;
    private final RateLimits rates;
    private final ThreadLocal<Snapshot> active=new ThreadLocal<>();
    private final ToolRegistry registry;
    private final ToolExecutor executor;
    private final Semaphore toolCapacity=new Semaphore(4);
    private final Semaphore capacity;
    private final Semaphore inspectionCapacity=new Semaphore(8);

    GatewayEngine(Settings settings, Contracts contracts, Inspector inspector, Provider provider, Audit audit) {
        this(settings,contracts,inspector,provider,audit,new ToolRegistry(contracts),new DemoTools());
    }
    GatewayEngine(Settings settings, Contracts contracts, Inspector inspector, Provider provider, Audit audit,ToolRegistry registry,ToolExecutor executor) {
        this(settings,contracts,inspector,provider,audit,registry,executor,null,new LocalRateLimits());
    }
    GatewayEngine(Settings settings,Contracts contracts,Inspector inspector,Provider provider,Audit audit,ToolRegistry registry,ToolExecutor executor,DurableState state,RateLimits rates) {
        this.state=state;this.rates=rates;
        this.registry=registry;this.executor=executor;
        this.settings=settings; this.contracts=contracts; this.inspector=inspector; this.provider=provider; this.audit=audit;
        try {
            initialPolicy=settings.policyPath().isBlank() ? contracts.resource("policies/support-default-v1.json")
                    : Contracts.parse(Files.readAllBytes(Path.of(settings.policyPath())),false);
            contracts.validate("policy",initialPolicy,false);
            if (state==null && settings.identities().values().stream().anyMatch(i->!i.tenant().equals(initialPolicy.path("tenant_id").asText())))
                throw new IllegalArgumentException();
        } catch (Exception ex) { throw new IllegalStateException("Invalid policy configuration."); }
        registry.policy(initialPolicy);
        capacity=new Semaphore(limit("max_concurrent_provider_calls"));
    }
    private int limit(String name) { return policy().path("limits").path(name).asInt(); }
    private JsonNode policy() {Snapshot current=active.get();return current==null?initialPolicy:current.policy();}
    private ToolRegistry registry() {Snapshot current=active.get();return current==null?registry:current.registry();}
    private void rate(Identity identity) {rates.check(identity,policy(),"work");}
    ObjectNode run(String kind,JsonNode request,Identity identity,String operation) {
        Snapshot snapshot=state==null?new Snapshot(initialPolicy.path("version").asLong(),initialPolicy,registry):state.snapshot(identity);
        active.set(snapshot);boolean finalizing=false;
        try {
            if(kind.equals("discovery")){rates.check(identity,policy(),"discovery");return tools(identity);}
            if(state!=null)state.start(operation,identity,kind,snapshot);
            try {
                ObjectNode result=switch(kind){case "chat"->chat(request,identity,operation);case "preview"->preview(request,identity,operation);case "tool"->execute(request,identity,operation);default->throw ApiFailure.invalid();};
                finalizing=true;if(state!=null)state.finish(operation,identity,200,"");return result;
            } catch(ApiFailure ex){if(state!=null&&!finalizing)state.finish(operation,identity,ex.status,ex.code);throw ex;}
            catch(Exception ex){if(state!=null&&!finalizing)state.finish(operation,identity,503,"DEPENDENCY_UNAVAILABLE");throw ApiFailure.unavailable();}
        } finally {active.remove();}
    }
    DurableState management(Identity identity,boolean update) {
        boolean authorized=update?identity.roles().contains("operator"):identity.roles().contains("operator")||identity.roles().contains("viewer");
        if(!authorized)throw new ApiFailure(403,"POLICY_DENIED");
        if(state==null)throw ApiFailure.unavailable();rates.check(identity,initialPolicy,"management");return state;
    }
    void ready() {rates.ready();if(state!=null)state.snapshot(settings.identities().values().iterator().next());}
    ObjectNode preview(JsonNode request, Identity identity, String operationId) {
        contracts.validate("inspect-request",request,false); requireAgent(identity); rate(identity);
        List<Segment> segments=new ArrayList<>();
        request.path("segments").forEach(n->segments.add(new Segment(n.path("id").asText(),n.path("stage").asText(),n.path("path").asText(),n.path("text").asText())));
        Evaluation result=evaluate(operationId,"preview",segments);
        audit.write(result.decision(),identity,"none",result.decision().path("action").asText().equals("deny")?"blocked":"approved",0);
        ObjectNode response=Contracts.object().set("decision",result.decision());
        if (result.decision().path("action").asText().equals("redact")) { var replacements=response.putArray("replacement_segments"); result.sanitized().forEach(s->replacements.add(s.json())); }
        contracts.validate("inspect-response",response,true); return response;
    }
    ObjectNode chat(JsonNode request, Identity identity, String operationId) {
        contracts.validate("chat-request",request,false); requireAgent(identity);
        if (!identity.models().contains(request.path("model").asText())) throw new ApiFailure(403,"POLICY_DENIED");
        String schemaId=request.path("sentinel").path("output_schema_id").asText("");
        Set<String> requestedTools=new LinkedHashSet<>();
        try {
            request.path("sentinel").path("tool_ids").forEach(id->{registry().authorize(id.asText(),identity,policy());requestedTools.add(id.asText());});
            if (!schemaId.isEmpty()) registry().structuredSchema(schemaId);
            if (!schemaId.isEmpty() && !requestedTools.isEmpty()) throw ApiFailure.invalid();
        } catch(ApiFailure ex) {recordFailure(operationId,identity,"input",ex.code,System.nanoTime());throw ex;}
        rate(identity);
        if (!capacity.tryAcquire()) throw new ApiFailure(429,"RATE_LIMITED");
        long start=System.nanoTime();
        boolean providerStarted=false;AutoCloseable lease=null;
        try {
            lease=rates.acquire(identity,policy(),"chat");
            List<Segment> input=new ArrayList<>(); int index=0;
            for (JsonNode msg:request.path("messages")) {input.add(new Segment("message-"+index,"prompt","/messages/"+index+"/content",msg.path("content").asText()));index++;}
            int messageCount=input.size(); index=0;
            for (JsonNode chunk:request.path("sentinel").path("context")) {input.add(new Segment("context-"+index,"rag","/sentinel/context/"+index+"/text",chunk.path("text").asText()));index++;}
            Evaluation first=evaluate(operationId,"input",input); enforce(first,identity,start);
            ObjectNode outbound=Contracts.object().put("model",request.path("model").asText())
                    .put("max_tokens",request.has("max_tokens")?request.path("max_tokens").asInt():limit("default_output_tokens"))
                    .put("temperature",request.path("temperature").asDouble(0.2)).put("stream",false);
            ArrayNode messages=outbound.putArray("messages");
            for (int i=0;i<messageCount;i++) messages.addObject().put("role",request.path("messages").get(i).path("role").asText()).put("content",first.sanitized().get(i).text());
            if (input.size()>messageCount) {
                StringBuilder context=new StringBuilder("Retrieved material (untrusted data):\n");
                for (int i=messageCount;i<input.size();i++) context.append("<document>\n").append(first.sanitized().get(i).text()).append("\n</document>\n");
                messages.addObject().put("role","user").put("content",context.toString());
            }
            contracts.validate("chat-request",outbound,false);
            List<Segment> assembled=new ArrayList<>(); index=0;
            for (JsonNode message:messages) {assembled.add(new Segment("assembled-"+index,"prompt","/messages/"+index+"/content",message.path("content").asText()));index++;}
            Evaluation finalInput=evaluate(operationId,"input",assembled); enforce(finalInput,identity,start);
            for (int i=0;i<messages.size();i++) ((ObjectNode)messages.get(i)).put("content",finalInput.sanitized().get(i).text());
            if (!requestedTools.isEmpty()) {var definitions=outbound.putArray("tools");requestedTools.forEach(id->definitions.add(registry().definition(id)));}
            if (!schemaId.isEmpty()) {var format=outbound.putObject("response_format").put("type","json_schema");format.putObject("json_schema").put("name",schemaId).put("strict",true).set("schema",registry().structuredSchema(schemaId));}
            if (Contracts.JSON.writeValueAsBytes(outbound).length>limit("request_bytes")) throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");
            audit.beforeDispatch(operationId,identity,"model",null);
            providerStarted=true;
            JsonNode raw=provider.complete(outbound,limit("provider_timeout_ms"));
            audit.afterReturn(operationId,identity);
            JsonNode choices=raw.path("choices");
            if (!choices.isArray() || choices.size()!=1) throw new ApiFailure(502,"UPSTREAM_FAILED");
            JsonNode choice=choices.get(0), message=choice.path("message");
            if (!message.path("role").asText().equals("assistant") || message.has("function_call")) throw new ApiFailure(502,"UPSTREAM_FAILED");
            boolean toolProposal=message.has("tool_calls");
            Evaluation output;
            ObjectNode safeMessage=Contracts.object().put("role","assistant");
            if (toolProposal) {
                if (!choice.path("finish_reason").asText().equals("tool_calls") || !schemaId.isEmpty() || !(message.path("content").isNull()||message.path("content").isMissingNode())) throw new ApiFailure(502,"UPSTREAM_FAILED");
                ArrayNode approvedCalls=Contracts.array();List<Segment> arguments=new ArrayList<>();Set<String> callIds=new HashSet<>();
                JsonNode proposals=message.path("tool_calls");
                if (!proposals.isArray() || proposals.isEmpty() || proposals.size()>4) throw new ApiFailure(502,"UPSTREAM_FAILED");
                for (JsonNode call:proposals) {
                    String id=call.path("id").asText(), name=registry().toolName(call.path("function").path("name").asText());
                    if (!id.matches("[A-Za-z0-9_-]{1,128}") || !callIds.add(id) || !call.path("type").asText().equals("function") || !call.path("function").path("arguments").isTextual() || call.path("function").path("arguments").asText().length()>4096) throw new ApiFailure(502,"UPSTREAM_FAILED");
                    registry().authorize(name,identity,policy());
                    if (!requestedTools.contains(name)) throw new ApiFailure(403,"POLICY_DENIED");
                    JsonNode args=Contracts.parse(call.path("function").path("arguments").asText().getBytes(StandardCharsets.UTF_8),true);
                    try {registry().input(name,args);} catch(ApiFailure ex) {throw new ApiFailure(502,"UPSTREAM_FAILED");}
                    flatten(args,"/calls/"+approvedCalls.size()+"/arguments","tool_arguments",arguments);
                    var approved=approvedCalls.addObject().put("id",id).put("type","function");approved.putObject("function").put("name",name).put("arguments",Contracts.JSON.writeValueAsString(args));
                }
                output=evaluate(operationId,"tool_input",arguments);enforce(output,identity,start);
                safeMessage.putNull("content");safeMessage.set("tool_calls",approvedCalls);
            } else {
                if (!message.path("content").isTextual() || !Set.of("stop","length").contains(choice.path("finish_reason").asText())) throw new ApiFailure(502,"UPSTREAM_FAILED");
                String outputText=message.path("content").asText();
                if (outputText.codePointCount(0,outputText.length())>16384) throw new ApiFailure(502,"UPSTREAM_FAILED");
                if (schemaId.isEmpty()) {
                    output=evaluate(operationId,"output",List.of(new Segment("response-0","response","/choices/0/message/content",outputText)));
                    enforce(output,identity,start);safeMessage.put("content",output.sanitized().getFirst().text());
                } else {
                    if(!choice.path("finish_reason").asText().equals("stop")) throw new ApiFailure(502,"UPSTREAM_FAILED");
                    JsonNode value=Contracts.parse(outputText.getBytes(StandardCharsets.UTF_8),true);registry().structured(schemaId,value);
                    List<Segment> parts=new ArrayList<>();flatten(value,"","response",parts);
                    output=evaluate(operationId,"output",parts);
                    if(output.decision().path("action").asText().equals("deny")) enforce(output,identity,start);
                    JsonNode safe=replaceStrings(value,output.sanitized());registry().structured(schemaId,safe);enforce(output,identity,start);
                    safeMessage.put("content",Contracts.JSON.writeValueAsString(safe));
                }
            }
            ObjectNode response=Contracts.object().put("id","chatcmpl-"+operationId).put("object","chat.completion")
                    .put("created",System.currentTimeMillis()/1000).put("model",request.path("model").asText());
            var safeChoice=response.putArray("choices").addObject().put("index",0).put("finish_reason",choice.path("finish_reason").asText());
            safeChoice.set("message",safeMessage);
            var metadata=response.putObject("sentinel").put("operation_id",operationId).put("policy_id",policy().path("id").asText())
                    .put("policy_version",policy().path("version").asInt()).put("registry_version",registry().snapshot.path("version").asInt()).put("provider_mode",settings.provider())
                    .put("input_action",first.decision().path("action").asText().equals("redact")?"redact":finalInput.decision().path("action").asText())
                    .put("output_action",output.decision().path("action").asText());
            metadata.putArray("decision_ids").add(first.decision().path("decision_id").asText()).add(finalInput.decision().path("decision_id").asText()).add(output.decision().path("decision_id").asText());
            contracts.validate("chat-response",response,true); return response;
        } catch (ApiFailure ex) {
            if (ex.status>=500 || ex.status==403 && ex.decisionId==null) recordFailure(operationId,identity,providerStarted?"output":"input",ex.code,start);
            throw ex;
        }
        catch (Exception ex) { recordFailure(operationId,identity,providerStarted?"output":"input","DEPENDENCY_UNAVAILABLE",start); throw ApiFailure.unavailable(); }
        finally { closeLease(lease);capacity.release(); }
    }
    ObjectNode tools(Identity identity) {
        var response=Contracts.object().put("registry_version",registry().snapshot.path("version").asInt());
        response.set("tools",registry().discover(identity,policy()));contracts.validate("tool-catalogue",response,true);return response;
    }
    ObjectNode execute(JsonNode request,Identity identity,String operationId) {
        contracts.validate("tool-request",request,false);rate(identity);long start=System.nanoTime();boolean started=false,acquired=false;AutoCloseable lease=null;
        try {
            String id=request.path("tool_id").asText();registry().authorize(id,identity,policy());registry().input(id,request.path("arguments"));
            List<Segment> parts=new ArrayList<>();flatten(request.path("arguments"),"","tool_arguments",parts);
            Evaluation input=evaluate(operationId,"tool_input",parts);input.decision().put("tool_id",id);enforce(input,identity,start);
            if(!toolCapacity.tryAcquire()) throw new ApiFailure(429,"RATE_LIMITED");acquired=true;
            lease=rates.acquire(identity,policy(),"tool");
            audit.beforeDispatch(operationId,identity,"tool",id);
            started=true;JsonNode result=executor.execute(id,request.path("arguments").deepCopy(),5000);audit.afterReturn(operationId,identity);registry().output(id,result);
            parts=new ArrayList<>();flatten(result,"","tool_result",parts);
            Evaluation output=evaluate(operationId,"tool_output",parts);output.decision().put("tool_id",id);
            if(output.decision().path("action").asText().equals("deny")) enforce(output,identity,start);
            JsonNode safe=replaceStrings(result,output.sanitized());registry().output(id,safe);enforce(output,identity,start);
            var response=Contracts.object().put("operation_id",operationId).put("tool_id",id);response.set("result",safe);
            response.putArray("decision_ids").add(input.decision().path("decision_id").asText()).add(output.decision().path("decision_id").asText());
            contracts.validate("tool-response",response,true);return response;
        } catch(ApiFailure ex) {
            if (ex.decisionId==null) recordFailure(operationId,identity,started?"tool_output":"tool_input",ex.code,start);
            throw ex;
        } catch(Exception ex) {recordFailure(operationId,identity,started?"tool_output":"tool_input","OUTCOME_UNCERTAIN",start);throw new ApiFailure(503,"OUTCOME_UNCERTAIN");}
        finally {closeLease(lease);if(acquired) toolCapacity.release();}
    }
    private static void closeLease(AutoCloseable lease){if(lease!=null)try{lease.close();}catch(Exception ignored){/* Admission lease expires. */}}
    // Registry schemas have closed, reviewed property names; inspect every string value without JSON escape bypasses.
    private static void flatten(JsonNode value,String path,String stage,List<Segment> result) {
        if(value.isTextual()) result.add(new Segment("field-"+result.size(),stage,path,value.asText()));
        else if(value.isObject()) value.fields().forEachRemaining(e->flatten(e.getValue(),path+"/"+e.getKey().replace("~","~0").replace("/","~1"),stage,result));
        else if(value.isArray()) for(int i=0;i<value.size();i++) flatten(value.get(i),path+"/"+i,stage,result);
        if(result.size()>40) throw new ApiFailure(413,"PAYLOAD_TOO_LARGE");
    }
    private static JsonNode replaceStrings(JsonNode value,List<Segment> parts) {
        JsonNode safe=value.deepCopy();
        for(Segment part:parts) {
            int split=part.path().lastIndexOf('/');if(split<0) return TextNode.valueOf(part.text());
            JsonNode parent=safe.at(part.path().substring(0,split));String key=part.path().substring(split+1).replace("~1","/").replace("~0","~");
            if(parent.isObject()) ((ObjectNode)parent).put(key,part.text());else ((ArrayNode)parent).set(Integer.parseInt(key),TextNode.valueOf(part.text()));
        }
        return safe;
    }
    private void requireAgent(Identity identity) { if (!identity.roles().contains("support_agent")) throw new ApiFailure(403,"POLICY_DENIED"); }
    private void recordFailure(String operationId,Identity identity,String stage,String code,long start) {
        var decision=Contracts.object().put("decision_id",UUID.randomUUID().toString()).put("operation_id",operationId)
                .put("policy_id",policy().path("id").asText()).put("policy_version",policy().path("version").asInt()).put("registry_version",registry().snapshot.path("version").asInt()).put("stage",stage).put("action","deny");
        decision.putArray("findings");decision.putArray("reason_codes").add(code);
        try {audit.write(decision,identity,settings.provider(),code.equals("OUTCOME_UNCERTAIN")?"uncertain":Set.of("POLICY_DENIED","INVALID_REQUEST","RATE_LIMITED").contains(code)?"blocked":"dependency_failure",(System.nanoTime()-start)/1_000_000);}
        catch (ApiFailure ignored) { /* Audit unavailable: preserve fail-closed response; no payload fallback. */ }
    }
    private void enforce(Evaluation result, Identity identity, long start) {
        boolean denied=result.decision().path("action").asText().equals("deny");
        audit.write(result.decision(),identity,settings.provider(),denied?"blocked":Set.of("output","tool_output").contains(result.decision().path("stage").asText())?"completed":"approved",(System.nanoTime()-start)/1_000_000);
        if (denied) throw new ApiFailure(403,"POLICY_DENIED",result.decision().path("decision_id").asText());
    }
    Evaluation evaluate(String operationId, String stage, List<Segment> segments) {
        boolean empty=segments.isEmpty();
        if(empty) segments=List.of(new Segment("empty","tool_result","",""));
        Map<String,Segment> byId=new HashMap<>();
        for (Segment segment:segments) if (byId.put(segment.id(),segment)!=null) throw ApiFailure.invalid();
        if (!inspectionCapacity.tryAcquire()) throw new ApiFailure(429,"RATE_LIMITED");
        JsonNode report;
        try { report=inspector.inspect(operationId,segments,limit("inspection_timeout_ms")); }
        finally { inspectionCapacity.release(); }
        contracts.validate("inspection-response",report,true);
        if (!report.path("operation_id").asText().equals(operationId) || !report.path("status").asText().equals("complete") || report.path("truncated").asBoolean()) throw ApiFailure.unavailable();
        String action="allow"; Map<String,List<int[]>> spans=new HashMap<>();
        for (JsonNode finding:report.path("findings")) {
            Segment segment=byId.get(finding.path("segment_id").asText());
            String category=finding.path("category").asText(), rule=finding.path("rule_id").asText();
            if (segment==null || !category.equals(RULES.get(rule)) || !finding.path("detector_version").asText().equals(report.path("detector_versions").path(category).asText())) throw ApiFailure.unavailable();
            int begin=finding.path("start_byte").asInt(), end=finding.path("end_byte").asInt();
            checkSpan(segment.text(),begin,end);
            String selected=policy().path("actions").path(category).path(segment.stage()).asText();
            if (selected.equals("deny")) action="deny";
            else if (selected.equals("redact")) {
                if (segment.stage().equals("tool_arguments")) action="deny";
                else {if (!action.equals("deny")) action="redact"; spans.computeIfAbsent(segment.id(),k->new ArrayList<>()).add(new int[]{begin,end});}
            }
        }
        ObjectNode decision=Contracts.object().put("decision_id",UUID.randomUUID().toString()).put("operation_id",operationId)
                .put("policy_id",policy().path("id").asText()).put("policy_version",policy().path("version").asInt()).put("registry_version",registry().snapshot.path("version").asInt()).put("stage",stage).put("action",action);
        decision.set("findings",report.path("findings").deepCopy());
        decision.putArray("reason_codes").add(action.equals("deny")?"CONTENT_DENIED":action.equals("redact")?"SENSITIVE_DATA_REDACTED":"CONTENT_APPROVED");
        List<Segment> sanitized=new ArrayList<>();
        for (Segment segment:segments) sanitized.add(new Segment(segment.id(),segment.stage(),segment.path(),redact(segment.text(),spans.getOrDefault(segment.id(),List.of()))));
        return new Evaluation(decision,empty?List.of():List.copyOf(sanitized));
    }
    private static void checkSpan(String text,int begin,int end) {
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
        if (begin<0 || begin>=end || end>bytes.length) throw ApiFailure.unavailable();
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,0,begin));
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,0,end));
        } catch (CharacterCodingException ex) { throw ApiFailure.unavailable(); }
    }
    static String redact(String text,List<int[]> spans) {
        byte[] raw=text.getBytes(StandardCharsets.UTF_8);
        var sorted=new ArrayList<>(spans); sorted.sort(Comparator.comparingInt(s->s[0]));
        List<int[]> merged=new ArrayList<>();
        for (int[] span:sorted) {
            checkSpan(text,span[0],span[1]);
            if (!merged.isEmpty() && span[0]<=merged.getLast()[1]) merged.getLast()[1]=Math.max(merged.getLast()[1],span[1]);
            else merged.add(span.clone());
        }
        ByteArrayOutputStream out=new ByteArrayOutputStream(); int cursor=0;
        for (int[] span:merged) {out.write(raw,cursor,span[0]-cursor);out.writeBytes("[REDACTED]".getBytes(StandardCharsets.UTF_8));cursor=span[1];}
        out.write(raw,cursor,raw.length-cursor); return out.toString(StandardCharsets.UTF_8);
    }
}
