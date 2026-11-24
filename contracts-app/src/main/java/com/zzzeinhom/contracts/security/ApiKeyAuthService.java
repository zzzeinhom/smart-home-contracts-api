package com.zzzeinhom.contracts.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzzeinhom.contracts.dto.IssuedApiKeyResponse;
import com.zzzeinhom.contracts.persistence.entity.TenantApiKeyEntity;
import com.zzzeinhom.contracts.persistence.repository.TenantApiKeyRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

@Service
public class ApiKeyAuthService {

    static final String REDIS_KEY_PREFIX = "auth:apikey:";
    static final String INVALID_SENTINEL = "__INVALID__";
    static final Duration POSITIVE_CACHE_TTL = Duration.ofMinutes(5);
    static final Duration NEGATIVE_CACHE_TTL = Duration.ofSeconds(60);
    private static final String RAW_KEY_PREFIX = "shc_live_";
    private static final int KEY_RANDOM_BYTES = 24;
    private static final int DISPLAY_PREFIX_LENGTH = 14;

    private final TenantApiKeyRepository tenantApiKeyRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiKeyAuthService(
            TenantApiKeyRepository tenantApiKeyRepository,
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper) {
        this.tenantApiKeyRepository = tenantApiKeyRepository;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<String> authenticate(String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            return Optional.empty();
        }

        String keyHash = sha256Hex(rawApiKey.trim());
        String redisKey = REDIS_KEY_PREFIX + keyHash;

        String cachedValue = stringRedisTemplate.opsForValue().get(redisKey);
        if (INVALID_SENTINEL.equals(cachedValue)) {
            return Optional.empty();
        }
        if (cachedValue != null && !cachedValue.isBlank()) {
            return Optional.of(cachedValue);
        }

        Instant now = Instant.now();
        Optional<TenantApiKeyEntity> entityOpt = tenantApiKeyRepository.findByKeyHash(keyHash);
        if (entityOpt.isPresent()) {
            TenantApiKeyEntity entity = entityOpt.get();
            boolean valid = entity.isActive()
                    && (entity.getExpiresAt() == null || entity.getExpiresAt().isAfter(now));
            if (valid && entity.getTenantId() != null && !entity.getTenantId().isBlank()) {
                String normalizedTenant = normalizeTenantId(entity.getTenantId());
                Duration ttl = resolvePositiveTtl(entity.getExpiresAt(), now);
                stringRedisTemplate.opsForValue().set(redisKey, normalizedTenant, ttl);
                return Optional.of(normalizedTenant);
            }
        }

        stringRedisTemplate.opsForValue().set(redisKey, INVALID_SENTINEL, NEGATIVE_CACHE_TTL);
        return Optional.empty();
    }

    @Transactional
    public IssuedApiKeyResponse issueKey(String tenantId, String keyName, Instant expiresAt) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank.");
        }
        if (keyName == null || keyName.isBlank()) {
            throw new IllegalArgumentException("keyName must not be blank.");
        }

        byte[] randomBytes = new byte[KEY_RANDOM_BYTES];
        secureRandom.nextBytes(randomBytes);
        String rawApiKey = RAW_KEY_PREFIX + HexFormat.of().formatHex(randomBytes);
        String keyPrefix = rawApiKey.substring(0, DISPLAY_PREFIX_LENGTH);
        String keyHash = sha256Hex(rawApiKey);
        String normalizedTenant = normalizeTenantId(tenantId);

        TenantApiKeyEntity saved = tenantApiKeyRepository.save(new TenantApiKeyEntity(
                UUID.randomUUID(),
                normalizedTenant,
                keyName.trim(),
                keyPrefix,
                keyHash,
                expiresAt));

        return new IssuedApiKeyResponse(
                saved.getId(),
                saved.getTenantId(),
                saved.getKeyName(),
                saved.getKeyPrefix(),
                rawApiKey,
                saved.getCreatedAt(),
                saved.getExpiresAt());
    }

    @Transactional
    public void revokeKey(UUID keyId) {
        TenantApiKeyEntity entity = tenantApiKeyRepository.findById(keyId)
                .orElseThrow(() -> new NoSuchElementException("API key not found: " + keyId));
        entity.revoke();
        tenantApiKeyRepository.save(entity);
        stringRedisTemplate.delete(REDIS_KEY_PREFIX + entity.getKeyHash());
    }

    @Transactional
    public void ensureBootstrapKey(String tenantId, String keyName, String rawApiKey) {
        if (rawApiKey == null || rawApiKey.isBlank()) {
            return;
        }
        String trimmedKey = rawApiKey.trim();
        String keyHash = sha256Hex(trimmedKey);
        if (tenantApiKeyRepository.findByKeyHash(keyHash).isPresent()) {
            return;
        }
        String effectiveTenant = (tenantId == null || tenantId.isBlank())
                ? "default"
                : normalizeTenantId(tenantId);
        String effectiveKeyName = (keyName == null || keyName.isBlank())
                ? "bootstrap-key"
                : keyName.trim();
        String keyPrefix = trimmedKey.substring(0, Math.min(DISPLAY_PREFIX_LENGTH, trimmedKey.length()));

        tenantApiKeyRepository.save(new TenantApiKeyEntity(
                UUID.randomUUID(),
                effectiveTenant,
                effectiveKeyName,
                keyPrefix,
                keyHash,
                null));
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", ex);
        }
    }

    private static Duration resolvePositiveTtl(Instant expiresAt, Instant now) {
        if (expiresAt == null) {
            return POSITIVE_CACHE_TTL;
        }
        Duration remaining = Duration.between(now, expiresAt);
        if (remaining.compareTo(POSITIVE_CACHE_TTL) < 0 && !remaining.isNegative() && !remaining.isZero()) {
            return remaining;
        }
        return POSITIVE_CACHE_TTL;
    }

    private static String normalizeTenantId(String tenantId) {
        return tenantId.trim().toLowerCase(Locale.ROOT);
    }
}
