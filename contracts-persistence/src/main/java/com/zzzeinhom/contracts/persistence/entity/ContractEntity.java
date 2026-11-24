package com.zzzeinhom.contracts.persistence.entity;

import com.zzzeinhom.contracts.domain.enums.ExtractionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "contracts")
public class ContractEntity {

    public static final String SOURCE_CONTRACT_UPLOAD = "CONTRACT_UPLOAD";
    public static final String SOURCE_GENERATED_QUOTATION = "GENERATED_QUOTATION";

    @Id
    @Column(name = "id", length = 64, nullable = false, updatable = false)
    private String id;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "file_name", length = 255, nullable = false)
    private String fileName;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "raw_text", columnDefinition = "TEXT", nullable = false)
    private String rawText;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private ExtractionStatus status;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "source_type", length = 32, nullable = false)
    private String sourceType = SOURCE_CONTRACT_UPLOAD;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ContractEntity() {
    }

    public ContractEntity(String id, String tenantId, String fileName, String contentType, String rawText) {
        this(id, tenantId, fileName, contentType, rawText, SOURCE_CONTRACT_UPLOAD);
    }

    public ContractEntity(
            String id,
            String tenantId,
            String fileName,
            String contentType,
            String rawText,
            String sourceType) {
        Instant now = Instant.now();
        this.id = id;
        this.tenantId = tenantId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.rawText = rawText;
        this.sourceType = (sourceType != null && !sourceType.isBlank()) ? sourceType.trim() : SOURCE_CONTRACT_UPLOAD;
        this.status = ExtractionStatus.PROCESSING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static ContractEntity generatedQuotation(String id, String tenantId, String fileName, String rawText) {
        ContractEntity entity = new ContractEntity(
                id, tenantId, fileName, "text/plain", rawText, SOURCE_GENERATED_QUOTATION);
        entity.markNeedsReview();
        return entity;
    }

    public void setSourceType(String sourceType) {
        if (sourceType != null && !sourceType.isBlank()) {
            this.sourceType = sourceType.trim();
            this.updatedAt = Instant.now();
        }
    }

    public void updateRawText(String rawText) {
        if (rawText != null && !rawText.isBlank()) {
            this.rawText = rawText;
            this.updatedAt = Instant.now();
        }
    }

    public void markNeedsReview() {
        this.status = ExtractionStatus.NEEDS_REVIEW;
        this.errorMessage = null;
        this.updatedAt = Instant.now();
    }

    public void markApproved() {
        this.status = ExtractionStatus.APPROVED;
        this.errorMessage = null;
        this.updatedAt = Instant.now();
    }

    public void markFailed(String errorMessage) {
        this.status = ExtractionStatus.FAILED;
        this.errorMessage = errorMessage;
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public String getRawText() {
        return rawText;
    }

    public ExtractionStatus getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getSourceType() {
        return sourceType;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
