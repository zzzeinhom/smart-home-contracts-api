package com.zzzeinhom.contracts.controller;

import com.zzzeinhom.contracts.domain.model.ExtractionResult;
import com.zzzeinhom.contracts.dto.QuotationGenerateRequest;
import com.zzzeinhom.contracts.security.TenantContext;
import com.zzzeinhom.contracts.service.QuotationGenerationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/quotations")
public class QuotationController {

    private final QuotationGenerationService quotationGenerationService;

    public QuotationController(QuotationGenerationService quotationGenerationService) {
        this.quotationGenerationService = quotationGenerationService;
    }

    @PostMapping("/generate")
    public ResponseEntity<ExtractionResult> generateQuotation(
            @Valid @RequestBody QuotationGenerateRequest request) {
        String tenantId = TenantContext.requireTenantId();
        ExtractionResult result = quotationGenerationService.generateAndPersistQuotation(tenantId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }
}
