# Database Schema & Cache Design (PostgreSQL 16 + PGVector + Redis)

## PostgreSQL Tables

1. **`contracts`**
   - Stores job lifecycle metadata and multi-tenant isolation fields (`id`, `job_id`, `tenant_id`).
   - Captures uploaded file reference/storage key, SHA-256 `content_hash`, `mime_type`, processing `status` (`PROCESSING`, `NEEDS_REVIEW`, `APPROVED`, `FAILED`), and `created_at` / `updated_at` timestamps.

2. **`extracted_data`**
   - Foreign key reference to `contracts` plus `tenant_id` for tenant-scoped queries.
   - Stores the raw LLM structured output as `JSONB`, deterministic `is_math_verified` boolean flag, HITL review status (`DRAFT_FOR_REVIEW`, `APPROVED`, `REJECTED`), and reviewer audit fields (`reviewed_by`, `reviewed_at`).

3. **`device_catalog`**
   - Stores standardized smart home hardware catalog entries (`category`, `protocol`, `normalized_name`, reference unit pricing).
   - Includes a `vector` embedding column indexed with a `pgvector` HNSW index for similarity-based BoM normalization (Phase 2 RAG).

## Redis Key Conventions
- **Async Job Status Polling:** `tenant:{tenantId}:job:{jobId}` — TTL-bounded cache storing lightweight job status and summary state to avoid hitting PostgreSQL during rapid client polling.
- **Upload Idempotency Guard:** `tenant:{tenantId}:idempotency:{contentHash}` — short-TTL key preventing duplicate extraction runs for identical contract uploads.
