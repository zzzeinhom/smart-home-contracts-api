package com.zzzeinhom.contracts.mcp.dto;

import com.zzzeinhom.contracts.domain.enums.ExtractionStatus;
import com.zzzeinhom.contracts.domain.model.Installment;

import java.math.BigDecimal;
import java.util.List;

public record PendingInstallmentItemDto(
        String extractionId,
        String clientName,
        String projectLocation,
        ExtractionStatus reviewStatus,
        BigDecimal totalAmount,
        String currency,
        boolean isMathVerified,
        List<Installment> installments,
        List<String> warnings
) {}
