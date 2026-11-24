CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE contracts (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100),
    raw_text TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_contracts_tenant_status ON contracts (tenant_id, status);
CREATE INDEX idx_contracts_created_at ON contracts (created_at DESC);

CREATE TABLE extracted_data (
    extraction_id VARCHAR(64) PRIMARY KEY REFERENCES contracts(id) ON DELETE CASCADE,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    payload JSONB NOT NULL,
    is_math_verified BOOLEAN NOT NULL DEFAULT FALSE,
    review_status VARCHAR(32) NOT NULL DEFAULT 'NEEDS_REVIEW',
    reviewed_by VARCHAR(120),
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_extracted_data_tenant_review ON extracted_data (tenant_id, review_status);

-- Prepared for Phase 2 Catalog RAG (1024 dimensions for multilingual BGE-M3 embeddings)
CREATE TABLE device_catalog (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    category VARCHAR(50) NOT NULL,
    normalized_name VARCHAR(255) NOT NULL,
    brand VARCHAR(100),
    model_code VARCHAR(100),
    protocol VARCHAR(50) NOT NULL,
    wiring_requirement VARCHAR(50) NOT NULL DEFAULT 'UNSPECIFIED',
    reference_unit_price NUMERIC(12, 2),
    currency VARCHAR(10) NOT NULL DEFAULT 'EGP',
    description TEXT,
    embedding vector(1024),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_device_catalog_tenant_category ON device_catalog (tenant_id, category);
CREATE INDEX idx_device_catalog_embedding_hnsw ON device_catalog USING hnsw (embedding vector_cosine_ops);
