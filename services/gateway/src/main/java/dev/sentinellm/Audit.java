package dev.sentinellm;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;

interface Audit {
    void write(JsonNode decision, Identity identity, String provider, String outcome, long elapsedMs);
    default void beforeDispatch(String operation,Identity identity,String kind,String tool) {}
    default void afterReturn(String operation,Identity identity) {}
}
final class AuditEvents {
    static com.fasterxml.jackson.databind.node.ObjectNode build(JsonNode decision,Identity identity,String provider,String outcome,long elapsedMs) {
        var event=Contracts.object().put("event_id",UUID.randomUUID().toString())
                .put("operation_id",decision.path("operation_id").asText()).put("decision_id",decision.path("decision_id").asText())
                .put("timestamp",Instant.now().toString()).put("tenant_id",identity.tenant()).put("application_id",identity.application())
                .put("policy_id",decision.path("policy_id").asText()).put("policy_version",decision.path("policy_version").asInt())
                .put("stage",decision.path("stage").asText()).put("action",decision.path("action").asText())
                .put("provider_mode",provider).put("outcome",outcome).put("duration_ms",elapsedMs);
        event.set("reason_codes",decision.path("reason_codes").deepCopy());
        if (decision.has("registry_version")) event.put("registry_version",decision.path("registry_version").asInt());
        if (decision.has("tool_id")) event.put("tool_id",decision.path("tool_id").asText());
        var rules=event.putArray("rule_ids"); var versions=event.putObject("detector_versions");
        decision.path("findings").forEach(f->{rules.add(f.path("rule_id").asText()); versions.put(f.path("category").asText(),f.path("detector_version").asText());});
        return event;
    }
}

/** Single-process metadata log; neither transactional nor tamper-proof. */
final class FileAudit implements Audit {
    private final Path path;
    FileAudit(Path path) { this.path=path; }
    public synchronized void write(JsonNode decision, Identity identity, String provider, String outcome, long elapsedMs) {
        var event=AuditEvents.build(decision,identity,provider,outcome,elapsedMs);
        try {
            if (path.toAbsolutePath().getParent()!=null) Files.createDirectories(path.toAbsolutePath().getParent());
            byte[] bytes=Contracts.JSON.writeValueAsBytes(event);
            try (FileChannel channel=FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND)) {
                ByteBuffer buffer=ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer);
                channel.write(ByteBuffer.wrap(new byte[]{'\n'})); channel.force(true);
            }
        } catch (Exception ex) { throw ApiFailure.unavailable(); }
    }
}
