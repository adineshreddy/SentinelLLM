package dev.sentinellm;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.networknt.schema.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

final class Contracts {
    static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(262144).maxNumberLength(128).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Map<String, JsonSchema> compiled = new HashMap<>();
    Contracts() {
        for (String name: List.of("chat-request","chat-response","inspect-request","inspect-response",
                "inspection-request","inspection-response","policy","audit-event","decision","error","registry","tool-request","tool-response","tool-catalogue","config-update","config-response","config-history","event-page","operation-trace")) {
            JsonNode expanded=expand(resource("schemas/"+name+".schema.json"),new HashSet<>());
            compiled.put(name,JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(expanded));
        }
    }
    JsonNode resource(String path) {
        try (InputStream in=getClass().getClassLoader().getResourceAsStream(path)) {
            if (in==null) throw new IllegalStateException("Missing packaged contract.");
            return JSON.readTree(in);
        } catch (IOException ex) { throw new IllegalStateException("Invalid packaged contract."); }
    }
    private JsonNode expand(JsonNode n, Set<String> stack) {
        if (n.isObject()) {
            if (n.has("$ref")) {
                String name=n.get("$ref").asText();
                if (!name.matches("[a-z-]+\\.schema\\.json") || !stack.add(name)) throw new IllegalStateException("Non-local or cyclic contract reference.");
                JsonNode result=expand(resource("schemas/"+name),stack); stack.remove(name); return result;
            }
            ObjectNode result=JSON.createObjectNode();
            n.fields().forEachRemaining(entry->{ if (!entry.getKey().equals("$id")) result.set(entry.getKey(),expand(entry.getValue(),stack)); });
            return result;
        }
        if (n.isArray()) { ArrayNode result=JSON.createArrayNode(); n.forEach(v->result.add(expand(v,stack))); return result; }
        return n;
    }
    void validate(String name, JsonNode value, boolean upstream) {
        try {
            validateUnicode(value);
            if (!compiled.get(name).validate(value).isEmpty()) throw new IllegalArgumentException();
        } catch (RuntimeException ex) { throw upstream ? ApiFailure.unavailable() : ApiFailure.invalid(); }
    }
    static JsonNode parse(byte[] bytes, boolean upstream) {
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonNode n=JSON.readTree(text);
            if (n==null) throw new IOException();
            validateUnicode(n); return n;
        } catch (Exception ex) { throw upstream ? ApiFailure.unavailable() : ApiFailure.invalid(); }
    }
    private static void validateUnicode(JsonNode n) {
        if (n.isTextual()) {
            String s=n.asText();
            for (int i=0;i<s.length();i++) {
                char c=s.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i>=s.length() || !Character.isLowSurrogate(s.charAt(i))) throw new IllegalArgumentException();
                } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException();
            }
        } else if (n.isContainerNode()) n.forEach(Contracts::validateUnicode);
    }
    static ObjectNode object() { return JSON.createObjectNode(); }
    static ArrayNode array() { return JSON.createArrayNode(); }
}
