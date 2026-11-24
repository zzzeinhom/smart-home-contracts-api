package com.zzzeinhom.contracts.dto;

import java.time.Instant;
import java.util.UUID;

public record IssuedApiKeyResponse(
        UUID id,
        String tenantId,
        String keyName,
        String keyPrefix,
        String rawApiKey,
        Instant createdAt,
        Instant expiresAt
) {
}
