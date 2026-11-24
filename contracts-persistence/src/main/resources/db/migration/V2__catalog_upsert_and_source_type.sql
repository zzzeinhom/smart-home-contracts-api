-- 1. Deduplicate existing catalog items per (tenant_id, normalized_name), keeping rows with embeddings first
DELETE FROM device_catalog
WHERE id IN (
    SELECT id
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY tenant_id, normalized_name
                   ORDER BY (embedding IS NOT NULL) DESC, created_at DESC, id DESC
               ) AS rn
        FROM device_catalog
    ) ranked
    WHERE rn > 1
);

ALTER TABLE device_catalog
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

ALTER TABLE device_catalog
    ADD CONSTRAINT uq_device_catalog_tenant_normalized_name UNIQUE (tenant_id, normalized_name);

-- 2. Track whether a record originated from an uploaded contract or an AI-generated quotation
ALTER TABLE contracts
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(32) NOT NULL DEFAULT 'CONTRACT_UPLOAD';
