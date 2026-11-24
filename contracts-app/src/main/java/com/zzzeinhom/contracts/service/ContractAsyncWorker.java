package com.zzzeinhom.contracts.service;

import com.zzzeinhom.contracts.ai.extractor.ContractStructuredExtractor;
import com.zzzeinhom.contracts.ai.ingestion.DocumentPayload;
import com.zzzeinhom.contracts.domain.enums.ExtractionStatus;
import com.zzzeinhom.contracts.domain.model.ExtractionResult;
import com.zzzeinhom.contracts.domain.validation.FinancialGuardrailValidator;
import com.zzzeinhom.contracts.persistence.entity.ContractEntity;
import com.zzzeinhom.contracts.persistence.entity.ExtractedDataEntity;
import com.zzzeinhom.contracts.persistence.repository.ContractRepository;
import com.zzzeinhom.contracts.persistence.repository.ExtractedDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ContractAsyncWorker {

    private static final Logger log = LoggerFactory.getLogger(ContractAsyncWorker.class);

    private final ContractStructuredExtractor extractor;
    private final CatalogNormalizationService catalogNormalizationService;
    private final ContractRepository contractRepository;
    private final ExtractedDataRepository extractedDataRepository;
    private final JobStatusCacheService jobStatusCacheService;
    private final TransactionTemplate transactionTemplate;

    public ContractAsyncWorker(
            ContractStructuredExtractor extractor,
            CatalogNormalizationService catalogNormalizationService,
            ContractRepository contractRepository,
            ExtractedDataRepository extractedDataRepository,
            JobStatusCacheService jobStatusCacheService,
            TransactionTemplate transactionTemplate) {
        this.extractor = extractor;
        this.catalogNormalizationService = catalogNormalizationService;
        this.contractRepository = contractRepository;
        this.extractedDataRepository = extractedDataRepository;
        this.jobStatusCacheService = jobStatusCacheService;
        this.transactionTemplate = transactionTemplate;
    }

    @Async("contractTaskExecutor")
    public void processContractAsync(String jobId, String tenantId, DocumentPayload payload) {
        try {
            ContractStructuredExtractor.ExtractionOutcome outcome = extractor.extract(payload);
            ExtractionResult normalized = normalizeMetadata(outcome.result(), jobId, ExtractionStatus.NEEDS_REVIEW);
            ExtractionResult catalogAudited = catalogNormalizationService.normalizeAndAudit(tenantId, normalized);
            ExtractionResult validated = FinancialGuardrailValidator.validateAndFlag(catalogAudited);

            transactionTemplate.executeWithoutResult(status -> {
                ContractEntity contract = contractRepository.findByIdAndTenantId(jobId, tenantId)
                        .orElseThrow(() -> new IllegalStateException("Contract job not found: " + jobId));
                contract.updateRawText(outcome.persistedSourceText());
                contract.markNeedsReview();
                contractRepository.save(contract);
                extractedDataRepository.save(new ExtractedDataEntity(jobId, tenantId, validated));
            });

            jobStatusCacheService.putStatus(tenantId, jobId, ExtractionStatus.NEEDS_REVIEW, null);
        } catch (Exception ex) {
            String errorMessage = (ex.getMessage() != null && !ex.getMessage().isBlank())
                    ? ex.getMessage()
                    : ex.getClass().getSimpleName();
            log.error("Async contract extraction failed for tenant={} jobId={}", tenantId, jobId, ex);

            try {
                transactionTemplate.executeWithoutResult(status ->
                        contractRepository.findByIdAndTenantId(jobId, tenantId).ifPresent(contract -> {
                            contract.markFailed(errorMessage);
                            contractRepository.save(contract);
                        }));
            } catch (Exception dbEx) {
                log.error("Failed to persist FAILED status for tenant={} jobId={}", tenantId, jobId, dbEx);
            }

            jobStatusCacheService.putStatus(tenantId, jobId, ExtractionStatus.FAILED, errorMessage);
        }
    }

    static ExtractionResult normalizeMetadata(ExtractionResult raw, String jobId, ExtractionStatus targetStatus) {
        return new ExtractionResult(
                jobId,
                targetStatus,
                raw != null ? raw.clientProfile() : null,
                raw != null ? raw.financialCommitments() : null,
                raw != null ? raw.contractTerms() : null,
                raw != null ? raw.billOfMaterials() : null,
                raw != null ? raw.warnings() : null,
                raw != null ? raw.clarificationQuestions() : null
        );
    }
}
