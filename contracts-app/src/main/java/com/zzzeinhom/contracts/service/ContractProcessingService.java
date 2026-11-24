package com.zzzeinhom.contracts.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzzeinhom.contracts.ai.extractor.ContractStructuredExtractor;
import com.zzzeinhom.contracts.ai.ingestion.DocumentPayload;
import com.zzzeinhom.contracts.domain.enums.ExtractionStatus;
import com.zzzeinhom.contracts.domain.model.ExtractionResult;
import com.zzzeinhom.contracts.domain.validation.FinancialGuardrailValidator;
import com.zzzeinhom.contracts.dto.JobStatusResponse;
import com.zzzeinhom.contracts.exception.InvalidContractStateException;
import com.zzzeinhom.contracts.exception.ResourceNotFoundException;
import com.zzzeinhom.contracts.persistence.entity.ContractEntity;
import com.zzzeinhom.contracts.persistence.entity.ExtractedDataEntity;
import com.zzzeinhom.contracts.persistence.repository.ContractRepository;
import com.zzzeinhom.contracts.persistence.repository.ExtractedDataRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ContractProcessingService {

    private final ContractStructuredExtractor extractor;
    private final CatalogNormalizationService catalogNormalizationService;
    private final ContractRepository contractRepository;
    private final ExtractedDataRepository extractedDataRepository;
    private final JobStatusCacheService jobStatusCacheService;
    private final ContractAsyncWorker contractAsyncWorker;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public ContractProcessingService(
            ContractStructuredExtractor extractor,
            CatalogNormalizationService catalogNormalizationService,
            ContractRepository contractRepository,
            ExtractedDataRepository extractedDataRepository,
            JobStatusCacheService jobStatusCacheService,
            ContractAsyncWorker contractAsyncWorker,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper) {
        this.extractor = extractor;
        this.catalogNormalizationService = catalogNormalizationService;
        this.contractRepository = contractRepository;
        this.extractedDataRepository = extractedDataRepository;
        this.jobStatusCacheService = jobStatusCacheService;
        this.contractAsyncWorker = contractAsyncWorker;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    public ExtractionResult processContract(DocumentPayload payload) {
        return processContract("default", payload);
    }

    public ExtractionResult processContract(String tenantId, DocumentPayload payload) {
        ContractStructuredExtractor.ExtractionOutcome outcome = extractor.extract(payload);
        ExtractionResult raw = outcome.result();
        String extractionId = (raw != null && raw.extractionId() != null && !raw.extractionId().isBlank())
                ? raw.extractionId()
                : "ext_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ExtractionStatus targetStatus = (raw != null && raw.status() == ExtractionStatus.FAILED)
                ? ExtractionStatus.FAILED
                : ExtractionStatus.NEEDS_REVIEW;
        ExtractionResult normalized = ContractAsyncWorker.normalizeMetadata(raw, extractionId, targetStatus);
        ExtractionResult audited = catalogNormalizationService.normalizeAndAudit(tenantId, normalized);
        return FinancialGuardrailValidator.validateAndFlag(audited);
    }

    public ExtractionResult processContractText(String rawText) {
        ExtractionResult rawResult = extractor.extractFromText(rawText);
        return FinancialGuardrailValidator.validateAndFlag(rawResult);
    }

    public String submitAsyncExtraction(String tenantId, DocumentPayload payload) {
        String jobId = "ext_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String safeFileName = (payload.fileName() != null && !payload.fileName().isBlank())
                ? payload.fileName().trim()
                : "contract";
        String initialPlaceholder = "[PENDING_INGESTION: " + safeFileName + "]";

        transactionTemplate.executeWithoutResult(status -> {
            ContractEntity entity = new ContractEntity(
                    jobId, tenantId, safeFileName, payload.contentType(), initialPlaceholder);
            contractRepository.save(entity);
        });

        jobStatusCacheService.putStatus(tenantId, jobId, ExtractionStatus.PROCESSING, null);
        contractAsyncWorker.processContractAsync(jobId, tenantId, payload);
        return jobId;
    }

    public JobStatusResponse getJobStatusOrResult(String tenantId, String jobId) {
        Optional<JobStatusCacheService.JobStatusSnapshot> cached = jobStatusCacheService.getStatus(tenantId, jobId);
        if (cached.isPresent()) {
            JobStatusCacheService.JobStatusSnapshot snapshot = cached.get();
            if (snapshot.status() == ExtractionStatus.PROCESSING || snapshot.status() == ExtractionStatus.FAILED) {
                return new JobStatusResponse(jobId, snapshot.status(), snapshot.errorMessage(), null);
            }
        }

        ContractEntity contract = findContractOrThrow(tenantId, jobId);
        if (contract.getStatus() == ExtractionStatus.PROCESSING || contract.getStatus() == ExtractionStatus.FAILED) {
            jobStatusCacheService.putStatus(tenantId, jobId, contract.getStatus(), contract.getErrorMessage());
            return new JobStatusResponse(jobId, contract.getStatus(), contract.getErrorMessage(), null);
        }

        ExtractedDataEntity extractedData = findExtractedDataOrThrow(tenantId, jobId);
        return new JobStatusResponse(jobId, contract.getStatus(), null, extractedData.getPayload());
    }

    public ExtractionResult applyClarifications(String tenantId, String jobId, Map<String, String> answers) {
        ContractEntity contract = findContractOrThrow(tenantId, jobId);
        ensureEditableState(contract, "clarified");
        ExtractedDataEntity existingData = findExtractedDataOrThrow(tenantId, jobId);

        String currentJson;
        try {
            currentJson = objectMapper.writeValueAsString(existingData.getPayload());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize existing extraction payload for job: " + jobId, ex);
        }

        ExtractionResult refinedRaw = extractor.refineWithClarifications(currentJson, answers);
        ExtractionResult normalized = ContractAsyncWorker.normalizeMetadata(
                refinedRaw, jobId, ExtractionStatus.NEEDS_REVIEW);
        ExtractionResult catalogAudited = catalogNormalizationService.normalizeAndAudit(tenantId, normalized);
        ExtractionResult validated = FinancialGuardrailValidator.validateAndFlag(catalogAudited);

        transactionTemplate.executeWithoutResult(status -> {
            ContractEntity lockedContract = findContractOrThrow(tenantId, jobId);
            ensureEditableState(lockedContract, "clarified");
            ExtractedDataEntity lockedData = findExtractedDataOrThrow(tenantId, jobId);

            lockedContract.markNeedsReview();
            lockedData.updatePayload(validated);
            contractRepository.save(lockedContract);
            extractedDataRepository.save(lockedData);
        });

        jobStatusCacheService.putStatus(tenantId, jobId, ExtractionStatus.NEEDS_REVIEW, null);
        return validated;
    }

    public ExtractionResult approveExtraction(String tenantId, String jobId, String reviewedBy) {
        String reviewer = (reviewedBy != null && !reviewedBy.isBlank()) ? reviewedBy.trim() : "engineer";

        ExtractionResult approvedPayload = transactionTemplate.execute(status -> {
            ContractEntity contract = findContractOrThrow(tenantId, jobId);
            ensureEditableState(contract, "approved");
            ExtractedDataEntity extractedData = findExtractedDataOrThrow(tenantId, jobId);

            contract.markApproved();
            extractedData.approve(reviewer);
            contractRepository.save(contract);
            extractedDataRepository.save(extractedData);
            return extractedData.getPayload();
        });

        jobStatusCacheService.putStatus(tenantId, jobId, ExtractionStatus.APPROVED, null);
        return approvedPayload;
    }

    private ContractEntity findContractOrThrow(String tenantId, String jobId) {
        return contractRepository.findByIdAndTenantId(jobId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Contract job not found: " + jobId));
    }

    private ExtractedDataEntity findExtractedDataOrThrow(String tenantId, String jobId) {
        return extractedDataRepository.findByExtractionIdAndTenantId(jobId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Extracted data not found for job: " + jobId));
    }

    private static void ensureEditableState(ContractEntity contract, String action) {
        if (contract.getStatus() == ExtractionStatus.PROCESSING || contract.getStatus() == ExtractionStatus.FAILED) {
            throw new InvalidContractStateException(
                    "Contract job '" + contract.getId() + "' in state " + contract.getStatus()
                            + " cannot be " + action + ".");
        }
    }
}
