package com.zzzeinhom.contracts.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class BootstrapApiKeyRunner implements ApplicationRunner {

    private final ApiKeyAuthService apiKeyAuthService;
    private final String bootstrapApiKey;
    private final String bootstrapTenantId;

    public BootstrapApiKeyRunner(
            ApiKeyAuthService apiKeyAuthService,
            @Value("${app.security.bootstrap-api-key:}") String bootstrapApiKey,
            @Value("${app.security.bootstrap-tenant-id:default}") String bootstrapTenantId) {
        this.apiKeyAuthService = apiKeyAuthService;
        this.bootstrapApiKey = bootstrapApiKey;
        this.bootstrapTenantId = bootstrapTenantId;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (bootstrapApiKey == null || bootstrapApiKey.isBlank()) {
            return;
        }
        String effectiveTenantId = (bootstrapTenantId == null || bootstrapTenantId.isBlank())
                ? "default"
                : bootstrapTenantId.trim();
        apiKeyAuthService.ensureBootstrapKey(effectiveTenantId, "bootstrap-key", bootstrapApiKey);
    }
}
