package com.zzzeinhom.contracts.domain.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.zzzeinhom.contracts.domain.enums.ExtractionStatus;
import java.util.List;

public record ExtractionResult(
        String extractionId,
        ExtractionStatus status,
        ClientProfile clientProfile,
        FinancialCommitments financialCommitments,
        ContractTerms contractTerms,
        List<BillOfMaterialsItem> billOfMaterials,
        List<String> warnings,
        @JsonPropertyDescription("Maximum of 3 critical engineering or commercial clarification questions for the engineer in the contract's primary language; empty list if none")
        List<ClarificationQuestion> clarificationQuestions
) {}
