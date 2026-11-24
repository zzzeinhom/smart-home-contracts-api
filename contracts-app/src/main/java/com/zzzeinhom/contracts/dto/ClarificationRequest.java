package com.zzzeinhom.contracts.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.Map;

public record ClarificationRequest(
        @NotEmpty(message = "answers map must not be empty")
        Map<String, String> answers
) {}
