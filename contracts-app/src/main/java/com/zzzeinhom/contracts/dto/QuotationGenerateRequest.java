package com.zzzeinhom.contracts.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.List;

public record QuotationGenerateRequest(
        @NotBlank(message = "siteSurveyNotes is required")
        String siteSurveyNotes,
        String clientName,
        String phone,
        String projectLocation,
        Boolean includeVat,
        BigDecimal discountAmount,
        BigDecimal installationPercentage,
        List<Integer> paymentSplitPercentages
) {}
