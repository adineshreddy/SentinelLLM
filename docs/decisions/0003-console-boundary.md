# ADR 0003 — A same-origin console BFF with server-held gateway identities

Status: accepted in Phase 4.

## Decision

Serve React static assets and a small allowlisted API facade from one Node.js service. Use separate operator/viewer console credentials and expiring HttpOnly sessions. Select backend identities server-side: viewer/operator for management, support_agent for the explicitly authorized operator demo. Java remains responsible for authorization, policy validation, snapshots, admission, execution, and durable evidence.

## Rationale

Direct browser-to-gateway calls would require distributing backend API keys to the browser. The BFF keeps these keys in container environment only and removes the need for cross-origin API access. Built-in Node HTTP, crypto, and fetch suffice for a bounded local demonstration, with no runtime npm dependencies. React/Vite/TypeScript and Playwright are build/test dependencies only.

## Consequences

The console adds a small runtime process, but no paid service. It does not replace the Java gateway or provide a generic arbitrary-URL proxy. Mutations require an operator session, trusted Origin, and CSRF token; login and session admission are bounded. Demo model execution requires mock mode to avoid accidental hosted cost. Sessions are single-instance memory state; production public deployment requires TLS and stronger/distributed identity and abuse controls.

Policy reset means append reviewed defaults at the expected revision, not drop PostgreSQL data. The support agent makes three explicit gateway operations and stops on denial. It is a presentable bounded workflow rather than a general agent runtime.
