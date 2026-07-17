-- Row-Level Security for tenant isolation (FR-TENANT-1): defense in depth beneath the
-- application's own scoping. Sessions running as gateway_reader see only rows whose tenant
-- matches the app.current_tenant setting — a crafted query cannot cross tenants even if
-- application-layer filtering were bypassed.
--
-- The application's own connection (table owner) is exempt: its scoping happens in code with
-- tenant resolved from the validated JWT. The RLS role is for reporting/integration sessions
-- and for proving isolation at the database itself. Documented trade-off in ADR-0003.

CREATE ROLE gateway_reader NOLOGIN;
GRANT USAGE ON SCHEMA public TO gateway_reader;
GRANT SELECT ON approval_requests, audit_log, mcp_servers, tools, policies, tenants
    TO gateway_reader;

ALTER TABLE approval_requests ENABLE ROW LEVEL SECURITY;
CREATE POLICY approval_tenant_isolation ON approval_requests FOR SELECT TO gateway_reader
    USING (tenant_slug = current_setting('app.current_tenant', true));

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY audit_tenant_isolation ON audit_log FOR SELECT TO gateway_reader
    USING (tenant_slug = current_setting('app.current_tenant', true));

ALTER TABLE mcp_servers ENABLE ROW LEVEL SECURITY;
CREATE POLICY server_tenant_isolation ON mcp_servers FOR SELECT TO gateway_reader
    USING (owner_tenant_id IS NULL OR owner_tenant_id = (
        SELECT t.id FROM tenants t WHERE t.slug = current_setting('app.current_tenant', true)));

ALTER TABLE policies ENABLE ROW LEVEL SECURITY;
CREATE POLICY policy_tenant_isolation ON policies FOR SELECT TO gateway_reader
    USING (tenant_id IS NULL OR tenant_id = (
        SELECT t.id FROM tenants t WHERE t.slug = current_setting('app.current_tenant', true)));
