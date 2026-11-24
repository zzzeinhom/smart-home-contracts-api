package com.zzzeinhom.contracts.ai.quotation;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.zzzeinhom.contracts.domain.model.ClarificationQuestion;
import com.zzzeinhom.contracts.domain.model.ClientProfile;
import com.zzzeinhom.contracts.domain.model.ContractTerms;

import java.util.List;

public record SurveyDecomposition(
        ClientProfile clientProfile,
        ContractTerms contractTerms,
        List<SurveyRequirementItem> requirements,
        @JsonPropertyDescription("Maximum of 3 critical engineering clarification questions in the survey's language; empty list if none")
        List<ClarificationQuestion> clarificationQuestions
) {}
