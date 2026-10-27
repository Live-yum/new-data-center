-- Incremental PostgreSQL / PostgreSQL-compatible Kingbase migration.
-- Review and apply manually after backup, following 20261026_iot.sql.
-- No automatic execution, no production data, no permission grants or credentials.
CREATE SEQUENCE IF NOT EXISTS iot_process_page_seq START WITH 1;
CREATE TABLE IF NOT EXISTS iot_process_page (
 id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, name VARCHAR(100) NOT NULL,
 description VARCHAR(500) NOT NULL DEFAULT '', version BIGINT NOT NULL DEFAULT 0,
 schema_version INTEGER NOT NULL, template VARCHAR(32) NOT NULL, bindings TEXT NOT NULL,
 refresh_seconds INTEGER NOT NULL, stale_after_seconds INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS iot_process_page_tenant_idx ON iot_process_page(tenant_id,id);
-- Serialize the bounded per-tenant page admission across service instances.
CREATE TABLE IF NOT EXISTS iot_process_tenant (tenant_id BIGINT PRIMARY KEY);
