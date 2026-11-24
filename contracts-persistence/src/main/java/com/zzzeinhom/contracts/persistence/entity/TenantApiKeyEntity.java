package com.zzzeinhom.contracts.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_api_keys")
public class TenantApiKeyEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId;

    @Column(name = "key_name", length = 100, nullable = false)
    private String keyName;

    @Column(name = "key_prefix", length = 16, nullable = false)
    private String keyPrefix;

    @Column(name = "key_hash", length = 64, nullable = false, unique = true)
    private String keyHash;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected TenantApiKeyEntity() {
    }

    public TenantApiKeyEntity(
            UUID id,
            String tenantId,
            String keyName,
            String keyPrefix,
            String keyHash,
            Instant expiresAt) {
        this.id = id != null ? id : UUID.randomUUID();
        this.tenantId = tenantId;
        this.keyName = keyName;
        this.keyPrefix = keyPrefix;
        this.keyHash = keyHash;
        this.active = true;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    public void revoke() {
        this.active = false;
    }

    public UUID getId() {
        return id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getKeyName() {
        return keyName;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public String getKeyHash() {
        return keyHash;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
