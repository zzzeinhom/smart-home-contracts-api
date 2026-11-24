package com.zzzeinhom.contracts.controller;

import com.zzzeinhom.contracts.dto.IssueApiKeyRequest;
import com.zzzeinhom.contracts.dto.IssuedApiKeyResponse;
import com.zzzeinhom.contracts.security.ApiKeyAuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/api-keys")
public class ApiKeyAdminController {

    private final ApiKeyAuthService apiKeyAuthService;

    public ApiKeyAdminController(ApiKeyAuthService apiKeyAuthService) {
        this.apiKeyAuthService = apiKeyAuthService;
    }

    @PostMapping
    public ResponseEntity<IssuedApiKeyResponse> issueApiKey(@Valid @RequestBody IssueApiKeyRequest request) {
        IssuedApiKeyResponse issued = apiKeyAuthService.issueKey(
                request.tenantId(),
                request.keyName(),
                request.expiresAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(issued);
    }

    @DeleteMapping("/{keyId}")
    public ResponseEntity<Void> revokeApiKey(@PathVariable UUID keyId) {
        apiKeyAuthService.revokeKey(keyId);
        return ResponseEntity.noContent().build();
    }
}
