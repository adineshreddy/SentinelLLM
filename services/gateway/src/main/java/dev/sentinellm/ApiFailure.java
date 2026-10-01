package dev.sentinellm;

/** Fixed public errors only: never include input or upstream exception text. */
public class ApiFailure extends RuntimeException {
    final int status;
    final String code;
    final String decisionId;
    public ApiFailure(int status, String code) { this(status, code, null); }
    public ApiFailure(int status, String code, String decisionId) {
        super(switch (code) {
            case "NOT_FOUND" -> "Requested resource was not found.";
            case "VERSION_CONFLICT" -> "Configuration changed; fetch the current revision before updating.";
            case "INVALID_REQUEST" -> "Request is malformed or unsupported.";
            case "UNAUTHENTICATED" -> "Authentication required.";
            case "POLICY_DENIED" -> "Operation blocked by security policy.";
            case "PAYLOAD_TOO_LARGE" -> "Payload exceeds the configured limit.";
            case "RATE_LIMITED" -> "Request or concurrency limit exceeded.";
            case "UPSTREAM_TIMEOUT" -> "Upstream deadline exceeded.";
            case "OUTCOME_UNCERTAIN" -> "Tool outcome could not be confirmed; do not retry automatically.";
            case "UPSTREAM_FAILED" -> "Upstream returned an unsupported response.";
            default -> "Required dependency unavailable.";
        });
        this.status = status; this.code = code; this.decisionId = decisionId;
    }
    static ApiFailure invalid() { return new ApiFailure(400, "INVALID_REQUEST"); }
    static ApiFailure unavailable() { return new ApiFailure(503, "DEPENDENCY_UNAVAILABLE"); }
}
