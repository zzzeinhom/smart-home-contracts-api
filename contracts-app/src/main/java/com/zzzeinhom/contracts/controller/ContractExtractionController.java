package com.zzzeinhom.contracts.controller;

import com.zzzeinhom.contracts.ai.ingestion.DocumentPayload;
import com.zzzeinhom.contracts.ai.ingestion.ExtractionMode;
import com.zzzeinhom.contracts.domain.model.ExtractionResult;
import com.zzzeinhom.contracts.dto.ApprovalRequest;
import com.zzzeinhom.contracts.dto.ClarificationRequest;
import com.zzzeinhom.contracts.dto.JobStatusResponse;
import com.zzzeinhom.contracts.security.TenantContext;
import com.zzzeinhom.contracts.service.ContractProcessingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/contracts/extract")
public class ContractExtractionController {

    private final ContractProcessingService processingService;

    public ContractExtractionController(ContractProcessingService processingService) {
        this.processingService = processingService;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> uploadContractAsync(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mode", defaultValue = "AUTO") ExtractionMode mode) throws IOException {
        String tenantId = TenantContext.requireTenantId();
        DocumentPayload payload = buildPayload(file, mode);
        String jobId = processingService.submitAsyncExtraction(tenantId, payload);

        return ResponseEntity.accepted().body(Map.of(
                "jobId", jobId,
                "status", "PROCESSING"
        ));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<JobStatusResponse> getJobStatusOrResult(@PathVariable String jobId) {
        String tenantId = TenantContext.requireTenantId();
        return ResponseEntity.ok(processingService.getJobStatusOrResult(tenantId, jobId));
    }

    @PostMapping("/{jobId}/clarify")
    public ResponseEntity<ExtractionResult> clarifyExtraction(
            @PathVariable String jobId,
            @Valid @RequestBody ClarificationRequest request) {
        String tenantId = TenantContext.requireTenantId();
        return ResponseEntity.ok(
                processingService.applyClarifications(tenantId, jobId, request.answers()));
    }

    @PostMapping("/{jobId}/approve")
    public ResponseEntity<ExtractionResult> approveExtraction(
            @PathVariable String jobId,
            @RequestBody(required = false) ApprovalRequest request) {
        String tenantId = TenantContext.requireTenantId();
        String reviewedBy = request != null ? request.reviewedBy() : null;
        return ResponseEntity.ok(processingService.approveExtraction(tenantId, jobId, reviewedBy));
    }

    @PostMapping("/sync")
    public ResponseEntity<ExtractionResult> uploadAndExtractSync(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mode", defaultValue = "AUTO") ExtractionMode mode) throws IOException {
        String tenantId = TenantContext.requireTenantId();
        DocumentPayload payload = buildPayload(file, mode);
        ExtractionResult result = processingService.processContract(tenantId, payload);
        return ResponseEntity.ok(result);
    }

    private static DocumentPayload buildPayload(MultipartFile file, ExtractionMode mode) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded contract file must not be empty.");
        }
        byte[] fileBytes = file.getBytes();
        if (fileBytes.length == 0) {
            throw new IllegalArgumentException("Uploaded contract file must not be empty.");
        }
        String fileName = (file.getOriginalFilename() != null && !file.getOriginalFilename().isBlank())
                ? file.getOriginalFilename().trim()
                : "contract";
        return new DocumentPayload(fileName, file.getContentType(), fileBytes, mode);
    }
}
