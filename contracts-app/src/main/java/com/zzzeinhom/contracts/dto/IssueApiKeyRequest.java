package com.zzzeinhom.contracts.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public record IssueApiKeyRequest(
        @NotBlank(message = "tenantId must not be blank") String tenantId,
        @NotBlank(message = "keyName must not be blank") String keyName,
        Instant expiresAt
) {
}
