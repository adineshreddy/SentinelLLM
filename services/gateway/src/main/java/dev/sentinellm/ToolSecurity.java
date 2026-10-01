package dev.sentinellm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.networknt.schema.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/** Trusted, packaged registry. No client schema, URL, or tool annotation grants authority. */
final class ToolRegistry {
    final JsonNode snapshot;
    private final Map<String,JsonNode> tools=new LinkedHashMap<>();
    private final Map<String,JsonSchema> inputs=new HashMap<>(),outputs=new HashMap<>(),structured=new HashMap<>();
    private static final Set<String> KEYWORDS=Set.of("$schema","type","properties","required","additionalProperties","items","minItems","maxItems","minLength","maxLength","pattern","enum","minimum","maximum");
    ToolRegistry(Contracts contracts) { this(contracts,contracts.resource("policies/tool-registry-v1.json")); }
    ToolRegistry(Contracts contracts,JsonNode source) {
        try {
            contracts.validate("registry",source,false);snapshot=source.deepCopy();
            for (JsonNode tool:snapshot.path("tools")) {
                String id=tool.path("tool_id").asText();
                if (tools.put(id,tool)!=null || !(id.equals("kb.search")&&tool.path("executor").asText().equals("demo_kb") || id.equals("ticket.get")&&tool.path("executor").asText().equals("demo_ticket"))) throw new IllegalArgumentException();
                inputs.put(id,compile(tool.path("input_schema")));outputs.put(id,compile(tool.path("output_schema")));
            }
            for (JsonNode item:snapshot.path("output_schemas")) if (structured.put(item.path("id").asText(),compile(item.path("schema")))!=null) throw new IllegalArgumentException();
        } catch (Exception ex) { throw new IllegalStateException("Invalid trusted tool registry."); }
    }
    private static JsonSchema compile(JsonNode schema) {
        restricted(schema,0);
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema);
    }
    private static void restricted(JsonNode schema,int depth) {
        if (!schema.isObject() || depth>8) throw new IllegalArgumentException();
        if (schema.has("$schema") && !schema.path("$schema").asText().equals("https://json-schema.org/draft/2020-12/schema")) throw new IllegalArgumentException();
        schema.fieldNames().forEachRemaining(k->{if (!KEYWORDS.contains(k)) throw new IllegalArgumentException();});
        if (!Set.of("object","array","string","integer","number","boolean").contains(schema.path("type").asText())) throw new IllegalArgumentException();
        if (schema.path("type").asText().equals("object") && !schema.path("additionalProperties").isBoolean()) throw new IllegalArgumentException();
        if (schema.path("additionalProperties").asBoolean()) throw new IllegalArgumentException();
        schema.path("properties").forEach(v->restricted(v,depth+1));
        if (schema.has("items")) restricted(schema.path("items"),depth+1);
        // Patterns are restricted to the two reviewed identifier formats; avoid configurable ReDoS.
        if (schema.has("pattern") && !Set.of("^[A-Za-z0-9_.-]{1,128}$","^DEMO-[0-9]{1,6}$").contains(schema.path("pattern").asText())) throw new IllegalArgumentException();
    }
    JsonNode tool(String id) { JsonNode tool=tools.get(id);if (tool==null) throw new ApiFailure(403,"POLICY_DENIED");return tool; }
    void policy(JsonNode policy) { policy.path("roles").forEach(ids->ids.forEach(id->tool(id.asText()))); }
    boolean allowed(String id,Identity identity,JsonNode policy) {
        return tools.containsKey(id)&&identity.tenant().equals(policy.path("tenant_id").asText())&&identity.roles().stream().anyMatch(role->{for(JsonNode name:policy.path("roles").path(role)) if(name.asText().equals(id)) return true;return false;});
    }
    void authorize(String id,Identity identity,JsonNode policy) { if (!allowed(id,identity,policy)) throw new ApiFailure(403,"POLICY_DENIED"); }
    void input(String id,JsonNode args) { tool(id);if (!inputs.get(id).validate(args).isEmpty()) throw ApiFailure.invalid(); }
    void output(String id,JsonNode result) { if (!outputs.get(id).validate(result).isEmpty()) throw new ApiFailure(502,"UPSTREAM_FAILED"); }
    JsonNode structuredSchema(String id) { if (!structured.containsKey(id)) throw new ApiFailure(403,"POLICY_DENIED");for(JsonNode item:snapshot.path("output_schemas")) if(item.path("id").asText().equals(id)) return item.path("schema").deepCopy();throw ApiFailure.invalid(); }
    void structured(String id,JsonNode value) { structuredSchema(id);if (!structured.get(id).validate(value).isEmpty()) throw new ApiFailure(502,"UPSTREAM_FAILED"); }
    ArrayNode discover(Identity identity,JsonNode policy) {
        ArrayNode result=Contracts.array();tools.forEach((id,t)->{if(allowed(id,identity,policy)) {ObjectNode visible=(ObjectNode)t.deepCopy();visible.remove("executor");result.add(visible);}});return result;
    }
    String functionName(String id) {tool(id);return id.replace('.','_');}
    String toolName(String function) {for(String id:tools.keySet()) if(functionName(id).equals(function)) return id;throw new ApiFailure(403,"POLICY_DENIED");}
    ObjectNode definition(String id) {var n=Contracts.object().put("type","function");n.putObject("function").put("name",functionName(id)).set("parameters",tool(id).path("input_schema").deepCopy());return n;}
}

interface ToolExecutor { JsonNode execute(String id,JsonNode arguments,int timeoutMs); }

/** Native read-only fixture executors for unit tests and standalone gateway development. */
final class DemoTools implements ToolExecutor {
    public JsonNode execute(String id,JsonNode arguments,int timeoutMs) {
        if (id.equals("kb.search")) {var result=Contracts.object();result.putArray("documents").addObject().put("id","kb-001").put("text","Reset your demo password from account settings.");return result;}
        if (id.equals("ticket.get")) return Contracts.object().put("ticket_id",arguments.path("ticket_id").asText()).put("status","open").put("summary","Demo support request awaiting review.");
        throw new ApiFailure(403,"POLICY_DENIED");
    }
}

/** Internal SDK bridge with a fixed, registered endpoint and bounded HTTP; no automatic retry. */
final class McpTools implements ToolExecutor {
    private final URI endpoint;private final String key;private final HttpTransport http;
    McpTools(String url,String key) { this(url,key,new BoundedHttp()); }
    McpTools(String url,String key,HttpTransport http) {
        endpoint=URI.create(url);
        if (!Set.of("http","https").contains(endpoint.getScheme()) || endpoint.getHost()==null || endpoint.getUserInfo()!=null || endpoint.getQuery()!=null || endpoint.getFragment()!=null || !endpoint.getPath().equals("/internal/v1/tools/execute") || key.length()<32) throw new IllegalArgumentException("Invalid registered MCP bridge configuration.");
        this.key=key;this.http=http;
    }
    public JsonNode execute(String id,JsonNode arguments,int timeoutMs) {
        ObjectNode request=Contracts.object().put("tool_id",id);request.set("arguments",arguments);
        try {
            JsonNode response=http.post(endpoint,key,request,timeoutMs,65536,false);
            if(!response.isObject() || response.size()!=2 || !response.path("tool_id").asText().equals(id) || !response.path("result").isObject()) throw ApiFailure.unavailable();
            return response.path("result");
        } catch (ApiFailure ex) { throw new ApiFailure(503,"OUTCOME_UNCERTAIN"); }
    }
}
