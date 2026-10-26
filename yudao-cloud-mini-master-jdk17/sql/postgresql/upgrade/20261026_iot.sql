-- Incremental PostgreSQL / PostgreSQL-compatible Kingbase migration.
-- Review and apply manually after backup. No DROP and no automatic execution.
CREATE SEQUENCE IF NOT EXISTS iot_device_seq START WITH 1;
CREATE SEQUENCE IF NOT EXISTS iot_dashboard_seq START WITH 1;
CREATE TABLE IF NOT EXISTS iot_device (
 id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, name VARCHAR(100) NOT NULL, code VARCHAR(64) NOT NULL,
 UNIQUE(tenant_id,code), UNIQUE(tenant_id,id)
);
CREATE TABLE IF NOT EXISTS iot_mapping (
 tenant_id BIGINT NOT NULL, device_id BIGINT NOT NULL, metric_key VARCHAR(64) NOT NULL,
 name VARCHAR(100) NOT NULL, point_name VARCHAR(256) NOT NULL, unit VARCHAR(32) NOT NULL,
 PRIMARY KEY(tenant_id,device_id,metric_key),
 FOREIGN KEY(tenant_id,device_id) REFERENCES iot_device(tenant_id,id)
);
CREATE TABLE IF NOT EXISTS iot_dashboard (
 id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, name VARCHAR(100) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, layout TEXT NOT NULL, is_template BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS iot_dashboard_tenant_idx ON iot_dashboard(tenant_id,is_template);
-- Private bounded exports stay in the relational DB, not the public infra file URL surface.
CREATE TABLE IF NOT EXISTS iot_export (
 id VARCHAR(36) PRIMARY KEY, tenant_id BIGINT NOT NULL, owner_id BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL, query_json TEXT NOT NULL, csv_content TEXT,
 error VARCHAR(200), created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL, started_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX IF NOT EXISTS iot_export_owner_idx ON iot_export(tenant_id,owner_id,created_at);
CREATE INDEX IF NOT EXISTS iot_export_pending_idx ON iot_export(status,created_at);

-- Serialize per-owner admission across service instances without holding a lock during upstream I/O.
CREATE TABLE IF NOT EXISTS iot_export_owner (
 tenant_id BIGINT NOT NULL, owner_id BIGINT NOT NULL, PRIMARY KEY(tenant_id,owner_id)
);
