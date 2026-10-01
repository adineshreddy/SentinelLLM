package dev.sentinellm;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.env.Environment;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;

record Identity(String tenant, String application, Set<String> roles, Set<String> models) {}

record Settings(Map<String, Identity> identities, String inspectionUrl, String inspectionKey,
                String provider, String navigatorBase, String navigatorKey, Path auditPath,
                String policyPath, String mockResponse) {
    @Override public String toString() { return "Settings[provider="+provider+", credentials=REDACTED]"; }
    static Settings load(Environment e) {
        try {
            JsonNode entries = Contracts.JSON.readTree(e.getProperty("SENTINEL_APPLICATION_KEYS_JSON", "[]"));
            if (!entries.isArray() || entries.isEmpty() || entries.size() > 64) throw new IllegalArgumentException();
            Map<String,Identity> identities = new HashMap<>();
            for (JsonNode entry : entries) {
                String hash = entry.path("sha256").asText();
                String tenant = entry.path("tenant_id").asText();
                String app = entry.path("application_id").asText();
                Set<String> roles = strings(entry.path("roles"));
                Set<String> models = strings(entry.path("models"));
                if (!hash.matches("[a-f0-9]{64}") || !tenant.matches("[A-Za-z0-9_.-]{1,128}")
                        || !app.matches("[A-Za-z0-9_.-]{1,128}") || models.isEmpty()
                        || roles.isEmpty() || !Set.of("support_agent", "viewer", "operator").containsAll(roles)
                        || identities.put(hash, new Identity(tenant,app,roles,models)) != null) throw new IllegalArgumentException();
            }
            String key = e.getProperty("SENTINEL_INSPECTION_KEY", "");
            String url = e.getProperty("SENTINEL_INSPECTION_URL", "http://127.0.0.1:8000/internal/v1/inspect");
            URI uri = URI.create(url);
            if (key.length() < 32 || !Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null
                    || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                    || !uri.getPath().equals("/internal/v1/inspect")) throw new IllegalArgumentException();
            String provider = e.getProperty("SENTINEL_PROVIDER", "mock");
            String navigatorKey = e.getProperty("NAVIGATOR_API_KEY", "");
            String base = e.getProperty("NAVIGATOR_BASE_URL", "https://api.ai.it.ufl.edu/v1");
            if (!Set.of("mock","navigator").contains(provider)) throw new IllegalArgumentException();
            if (provider.equals("navigator") && (!e.getProperty("SENTINEL_HOSTED_ENABLED","false").equals("true")
                    || navigatorKey.isBlank())) throw new IllegalArgumentException();
            base = NavigatorProvider.normalizeBase(base);
            return new Settings(Map.copyOf(identities),url,key,provider,base,navigatorKey,
                    Path.of(e.getProperty("SENTINEL_AUDIT_PATH","runtime/audit.jsonl")),
                    e.getProperty("SENTINEL_POLICY_PATH",""),
                    e.getProperty("SENTINEL_MOCK_RESPONSE","SentinelLLM mock: approved request."));
        } catch (Exception ex) {
            throw new IllegalStateException("Invalid gateway configuration. Check credentials, provider opt-in, and configured destinations.");
        }
    }
    private static Set<String> strings(JsonNode node) {
        if (!node.isArray() || node.size()>32) throw new IllegalArgumentException();
        Set<String> result = new HashSet<>();
        for (JsonNode value:node) {
            if (!value.isTextual() || value.asText().isBlank() || value.asText().length()>128) throw new IllegalArgumentException();
            result.add(value.asText());
        }
        return Set.copyOf(result);
    }
}
