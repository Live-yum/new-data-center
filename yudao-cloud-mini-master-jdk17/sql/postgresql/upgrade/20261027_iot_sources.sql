-- Manual, additive migration after reviewing/backing up a controlled database.
-- Stop and drain ALL old IoT service instances before applying and creating named-source devices.
-- Old query code ignores source_id. Mixed old/new service operation and direct downgrade are unsafe.
-- Existing devices belonged to exactly the legacy singleton: make that identity explicit.
-- Application APIs never reassign source_id. New-source movement requires a new device and bindings.
ALTER TABLE iot_device ADD COLUMN IF NOT EXISTS source_id VARCHAR(64) NOT NULL DEFAULT 'default';
-- Internal immutable resolved source/mapping plan. Contains no URLs, accounts, passwords or tokens.
ALTER TABLE iot_export ADD COLUMN IF NOT EXISTS snapshot_json TEXT;
-- An old queued/running query did not capture point mappings. Replaying it after a change is unsafe.
-- Already completed private CSV stays intact. Downloads continue to revalidate tenant/owner/default access.
UPDATE iot_export SET status='FAILED',error='Legacy query has no binding snapshot - create a new export',csv_content=NULL
 WHERE snapshot_json IS NULL AND status IN ('QUEUED','RUNNING');
CREATE INDEX IF NOT EXISTS iot_device_source_idx ON iot_device(tenant_id,source_id);
