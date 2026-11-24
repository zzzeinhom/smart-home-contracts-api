package com.zzzeinhom.contracts.controller;

import com.zzzeinhom.contracts.ai.ingestion.DocumentPayload;
import com.zzzeinhom.contracts.ai.ingestion.ExtractionMode;
import com.zzzeinhom.contracts.domain.model.ExtractionResult;
import com.zzzeinhom.contracts.dto.ApprovalRequest;
import com.zzzeinhom.contracts.dto.ClarificationRequest;
import com.zzzeinhom.contracts.dto.JobStatusResponse;
import com.zzzeinhom.contracts.service.ContractProcessingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mode", defaultValue = "AUTO") ExtractionMode mode) throws IOException {
        DocumentPayload payload = buildPayload(file, mode);
        String jobId = processingService.submitAsyncExtraction(resolveTenantId(tenantId), payload);

        return ResponseEntity.accepted().body(Map.of(
                "jobId", jobId,
                "status", "PROCESSING"
        ));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<JobStatusResponse> getJobStatusOrResult(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String jobId) {
        return ResponseEntity.ok(processingService.getJobStatusOrResult(resolveTenantId(tenantId), jobId));
    }

    @PostMapping("/{jobId}/clarify")
    public ResponseEntity<ExtractionResult> clarifyExtraction(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String jobId,
            @Valid @RequestBody ClarificationRequest request) {
        return ResponseEntity.ok(
                processingService.applyClarifications(resolveTenantId(tenantId), jobId, request.answers()));
    }

    @PostMapping("/{jobId}/approve")
    public ResponseEntity<ExtractionResult> approveExtraction(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String jobId,
            @RequestBody(required = false) ApprovalRequest request) {
        String reviewedBy = request != null ? request.reviewedBy() : null;
        return ResponseEntity.ok(processingService.approveExtraction(resolveTenantId(tenantId), jobId, reviewedBy));
    }

    @PostMapping("/sync")
    public ResponseEntity<ExtractionResult> uploadAndExtractSync(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "mode", defaultValue = "AUTO") ExtractionMode mode) throws IOException {
        DocumentPayload payload = buildPayload(file, mode);
        ExtractionResult result = processingService.processContract(resolveTenantId(tenantId), payload);
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

    private static String resolveTenantId(String tenantId) {
        return (tenantId == null || tenantId.isBlank()) ? "default" : tenantId.trim();
    }
}
