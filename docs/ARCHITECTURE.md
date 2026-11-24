# Architecture: Smart Home Contract Extraction Engine

## Overview
A multi-tenant-ready standalone RESTful microservice (B2B API) that ingests mixed Arabic/English smart home contracts and technical quotations (PDFs and scanned images), extracts structured business data using Spring AI, verifies financial math deterministically, and stages results for Human-in-the-Loop (HITL) review.

## Multi-Module Architecture & Dependency Boundaries
The Maven reactor enforces strict separation of concerns across four modules:

- **`contracts-domain`**: Pure Java 21 `record`s, enums, and deterministic financial math validation contracts. Zero Spring Web, JPA, or AI framework coupling.
- **`contracts-ai`**: Spring AI multimodal Vision parsing, prompt management, and `BeanOutputConverter` structured output adapters. Depends on `contracts-domain`.
- **`contracts-persistence`**: JPA entities, Spring Data repositories, PostgreSQL `JSONB` & `pgvector` mappings, and Flyway schema migrations. Depends on `contracts-domain`.
- **`contracts-app`**: Executable Spring Boot application, REST controllers, async orchestration, Redis job state caching, global exception handling, and Actuator endpoints. Depends on `contracts-domain`, `contracts-ai`, and `contracts-persistence`.

## Core Flow (Phase 1)
1. **Async Upload:** Client uploads a PDF or image via `POST`. The API registers the job, caches initial job state in Redis (`PROCESSING`), and replies immediately with `202 Accepted` and a `jobId` to prevent HTTP timeouts.
2. **Multimodal Vision Parsing:** `contracts-ai` submits document pages/images to a Spring AI Vision model capable of reading mixed Arabic/English text and RTL tables.
3. **Structured Extraction:** `BeanOutputConverter` maps the LLM response directly to Java 21 `record`s (`ClientProfile`, `FinancialCommitments`, `BillOfMaterialsItem`) plus extraction `warnings`.
4. **Deterministic Financial Validation:** Pure Java validators in `contracts-domain` sum all extracted installment amounts and compare them against `totalAmount`. Any mismatch sets `isMathVerified = false` and appends an explicit discrepancy warning.
5. **Human-in-the-Loop (HITL):** The validated payload is persisted to PostgreSQL (`JSONB`) in `DRAFT_FOR_REVIEW` / `NEEDS_REVIEW` state for operator confirmation prior to downstream ERP/CRM synchronization.

## Tech Stack
- **Runtime & Framework:** Java 21, Spring Boot 3.4.x, Spring AI 1.0.x
- **Database & Vector Store:** PostgreSQL 16 with `pgvector` (Phase 2 device catalog RAG matching)
- **Cache & Coordination:** Redis 7 (async job status caching and upload idempotency guards)
- **Migrations & Testing:** Flyway, JUnit 5, Testcontainers (`postgresql`)
- **Multi-Tenancy:** `tenant_id` isolation across database tables and Redis key spaces
