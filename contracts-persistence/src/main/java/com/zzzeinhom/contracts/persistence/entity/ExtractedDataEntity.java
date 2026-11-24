package com.zzzeinhom.contracts.persistence.entity;

import com.zzzeinhom.contracts.domain.enums.ExtractionStatus;
import com.zzzeinhom.contracts.domain.model.ExtractionResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "extracted_data")
public class ExtractedDataEntity {

    @Id
    @Column(name = "extraction_id", length = 64, nullable = false, updatable = false)
    private String extractionId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "extraction_id", referencedColumnName = "id", insertable = false, updatable = false)
    private ContractEntity contract;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private ExtractionResult payload;

    @Column(name = "is_math_verified", nullable = false)
    private boolean isMathVerified;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", length = 32, nullable = false)
    private ExtractionStatus reviewStatus;

    @Column(name = "reviewed_by", length = 120)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ExtractedDataEntity() {
    }

    public ExtractedDataEntity(String extractionId, String tenantId, ExtractionResult payload) {
        Instant now = Instant.now();
        this.extractionId = extractionId;
        this.tenantId = tenantId;
        this.payload = payload;
        this.isMathVerified = extractMathVerified(payload);
        this.reviewStatus = ExtractionStatus.NEEDS_REVIEW;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void updatePayload(ExtractionResult updatedPayload) {
        this.payload = updatedPayload;
        this.isMathVerified = extractMathVerified(updatedPayload);
        this.reviewStatus = ExtractionStatus.NEEDS_REVIEW;
        this.updatedAt = Instant.now();
    }

    public void approve(String reviewer) {
        Instant now = Instant.now();
        this.reviewStatus = ExtractionStatus.APPROVED;
        this.reviewedBy = reviewer;
        this.reviewedAt = now;
        this.updatedAt = now;
        if (this.payload != null) {
            this.payload = new ExtractionResult(
                    this.payload.extractionId(),
                    ExtractionStatus.APPROVED,
                    this.payload.clientProfile(),
                    this.payload.financialCommitments(),
                    this.payload.contractTerms(),
                    this.payload.billOfMaterials(),
                    this.payload.warnings(),
                    this.payload.clarificationQuestions()
            );
        }
    }

    private static boolean extractMathVerified(ExtractionResult result) {
        return result != null
                && result.financialCommitments() != null
                && result.financialCommitments().isMathVerified();
    }

    public String getExtractionId() {
        return extractionId;
    }

    public ContractEntity getContract() {
        return contract;
    }

    public String getTenantId() {
        return tenantId;
    }

    public ExtractionResult getPayload() {
        return payload;
    }

    public boolean isMathVerified() {
        return isMathVerified;
    }

    public ExtractionStatus getReviewStatus() {
        return reviewStatus;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
