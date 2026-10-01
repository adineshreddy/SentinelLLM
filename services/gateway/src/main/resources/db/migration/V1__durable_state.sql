CREATE TABLE config_versions (
    tenant_id varchar(128) NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    policy jsonb NOT NULL CHECK (jsonb_typeof(policy) = 'object'),
    registry jsonb NOT NULL CHECK (jsonb_typeof(registry) = 'object'),
    actor_application varchar(128) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, revision)
);
CREATE TABLE tenant_heads (
    tenant_id varchar(128) PRIMARY KEY,
    revision bigint NOT NULL,
    FOREIGN KEY (tenant_id, revision) REFERENCES config_versions(tenant_id, revision)
);
CREATE TABLE operations (
    tenant_id varchar(128) NOT NULL,
    operation_id uuid NOT NULL,
    application_id varchar(128) NOT NULL,
    kind varchar(16) NOT NULL CHECK (kind IN ('chat','preview','tool')),
    revision bigint NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('started','in_flight','completed','blocked','failed','uncertain')),
    external_state varchar(16) NOT NULL DEFAULT 'none' CHECK (external_state IN ('none','dispatching','returned')),
    external_kind varchar(16) NOT NULL DEFAULT 'none' CHECK (external_kind IN ('none','model','tool')),
    tool_id varchar(128),
    http_status integer,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    deadline_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, operation_id),
    FOREIGN KEY (tenant_id, revision) REFERENCES config_versions(tenant_id, revision)
);
CREATE TABLE audit_events (
    sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id varchar(128) NOT NULL,
    operation_id uuid NOT NULL,
    event_id uuid NOT NULL,
    event jsonb NOT NULL CHECK (jsonb_typeof(event) = 'object'),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id, event_id),
    FOREIGN KEY (tenant_id, operation_id) REFERENCES operations(tenant_id, operation_id)
);
CREATE INDEX audit_tenant_sequence ON audit_events (tenant_id, sequence DESC);
CREATE INDEX audit_tenant_operation ON audit_events (tenant_id, operation_id, sequence);
CREATE INDEX operations_tenant_created ON operations (tenant_id, created_at DESC);
CREATE TABLE management_events (
    tenant_id varchar(128) NOT NULL,
    event_id uuid NOT NULL,
    actor_application varchar(128) NOT NULL,
    previous_revision bigint NOT NULL,
    revision bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, event_id),
    FOREIGN KEY (tenant_id, revision) REFERENCES config_versions(tenant_id, revision)
);

-- Transaction-local tenant context plus explicit tenant predicates in the repository.
ALTER TABLE config_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE config_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_heads ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_heads FORCE ROW LEVEL SECURITY;
ALTER TABLE operations ENABLE ROW LEVEL SECURITY;
ALTER TABLE operations FORCE ROW LEVEL SECURITY;
ALTER TABLE audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_events FORCE ROW LEVEL SECURITY;
ALTER TABLE management_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE management_events FORCE ROW LEVEL SECURITY;
CREATE POLICY config_tenant ON config_versions USING (tenant_id = current_setting('sentinel.tenant', true)) WITH CHECK (tenant_id = current_setting('sentinel.tenant', true));
CREATE POLICY head_tenant ON tenant_heads USING (tenant_id = current_setting('sentinel.tenant', true)) WITH CHECK (tenant_id = current_setting('sentinel.tenant', true));
CREATE POLICY operation_tenant ON operations USING (tenant_id = current_setting('sentinel.tenant', true)) WITH CHECK (tenant_id = current_setting('sentinel.tenant', true));
CREATE POLICY audit_tenant ON audit_events USING (tenant_id = current_setting('sentinel.tenant', true)) WITH CHECK (tenant_id = current_setting('sentinel.tenant', true));
CREATE POLICY management_tenant ON management_events USING (tenant_id = current_setting('sentinel.tenant', true)) WITH CHECK (tenant_id = current_setting('sentinel.tenant', true));

GRANT USAGE ON SCHEMA public TO sentinel_app;
GRANT SELECT, INSERT ON config_versions, audit_events, management_events TO sentinel_app;
GRANT SELECT, INSERT, UPDATE ON tenant_heads, operations TO sentinel_app;
GRANT USAGE ON SEQUENCE audit_events_sequence_seq TO sentinel_app;
