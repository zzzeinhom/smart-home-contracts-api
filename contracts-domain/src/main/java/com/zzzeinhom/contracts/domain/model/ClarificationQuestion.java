package com.zzzeinhom.contracts.domain.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record ClarificationQuestion(
        @JsonPropertyDescription("Short sequential identifier such as q_1, q_2, q_3")
        String questionId,

        @JsonPropertyDescription("Target JSON section affected: clientProfile, financialCommitments, or billOfMaterials")
        String fieldTarget,

        @JsonPropertyDescription("Concise, high-impact question for the engineer in the same language as the contract")
        String question
) {}
