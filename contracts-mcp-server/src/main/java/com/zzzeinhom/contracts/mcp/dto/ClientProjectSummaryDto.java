package com.zzzeinhom.contracts.mcp.dto;

import com.zzzeinhom.contracts.domain.enums.ExtractionStatus;
import com.zzzeinhom.contracts.domain.model.BillOfMaterialsItem;
import com.zzzeinhom.contracts.domain.model.ClientProfile;
import com.zzzeinhom.contracts.domain.model.ContractTerms;

import java.math.BigDecimal;
import java.util.List;

public record ClientProjectSummaryDto(
        String extractionId,
        String sourceType,
        String fileName,
        ExtractionStatus reviewStatus,
        ClientProfile clientProfile,
        BigDecimal totalAmount,
        String currency,
        boolean isMathVerified,
        ContractTerms contractTerms,
        int billOfMaterialsCount,
        List<BillOfMaterialsItem> billOfMaterials,
        List<String> warnings
) {}
