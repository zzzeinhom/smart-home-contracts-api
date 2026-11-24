package com.zzzeinhom.contracts.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzzeinhom.contracts.dto.IssuedApiKeyResponse;
import com.zzzeinhom.contracts.persistence.entity.TenantApiKeyEntity;
import com.zzzeinhom.contracts.persistence.repository.TenantApiKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthServiceTest {

    @Mock
    private TenantApiKeyRepository tenantApiKeyRepository;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ApiKeyAuthService authService;

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        authService = new ApiKeyAuthService(tenantApiKeyRepository, stringRedisTemplate, new ObjectMapper());
    }

    @Test
    void authenticate_returnsCachedTenantWithoutQueryingRepositoryOnRedisHit() {
        String rawKey = "shc_live_1234567890abcdef1234567890abcdef";
        String expectedHash = ApiKeyAuthService.sha256Hex(rawKey);
        String redisKey = ApiKeyAuthService.REDIS_KEY_PREFIX + expectedHash;

        when(valueOperations.get(redisKey)).thenReturn("smart-power");

        Optional<String> tenantId = authService.authenticate(rawKey);

        assertThat(tenantId).contains("smart-power");
        verifyNoInteractions(tenantApiKeyRepository);
    }

    @Test
    void authenticate_returnsEmptyImmediatelyOnNegativeCacheHit() {
        String rawKey = "shc_live_invalid_cached";
        String redisKey = ApiKeyAuthService.REDIS_KEY_PREFIX + ApiKeyAuthService.sha256Hex(rawKey);

        when(valueOperations.get(redisKey)).thenReturn(ApiKeyAuthService.INVALID_SENTINEL);

        Optional<String> tenantId = authService.authenticate(rawKey);

        assertThat(tenantId).isEmpty();
        verifyNoInteractions(tenantApiKeyRepository);
    }

    @Test
    void authenticate_queriesBySha256HashOnMissAndCachesTenantForFiveMinutes() {
        String rawKey = "shc_live_valid_miss_key";
        String expectedHash = ApiKeyAuthService.sha256Hex(rawKey);
        String redisKey = ApiKeyAuthService.REDIS_KEY_PREFIX + expectedHash;
        TenantApiKeyEntity entity = new TenantApiKeyEntity(
                UUID.randomUUID(),
                "Smart-Power",
                "erp-integration",
                "shc_live_valid",
                expectedHash,
                Instant.now().plus(Duration.ofDays(30)));

        when(valueOperations.get(redisKey)).thenReturn(null);
        when(tenantApiKeyRepository.findByKeyHash(expectedHash)).thenReturn(Optional.of(entity));

        Optional<String> tenantId = authService.authenticate(rawKey);

        assertThat(tenantId).contains("smart-power");
        verify(tenantApiKeyRepository).findByKeyHash(expectedHash);
        verify(tenantApiKeyRepository, never()).findByKeyHash(rawKey);
        verify(valueOperations).set(redisKey, "smart-power", ApiKeyAuthService.POSITIVE_CACHE_TTL);
    }

    @Test
    void authenticate_negativeCachesUnknownOrExpiredKeysForSixtySeconds() {
        String unknownRawKey = "shc_live_unknown";
        String unknownHash = ApiKeyAuthService.sha256Hex(unknownRawKey);
        String unknownRedisKey = ApiKeyAuthService.REDIS_KEY_PREFIX + unknownHash;

        when(valueOperations.get(unknownRedisKey)).thenReturn(null);
        when(tenantApiKeyRepository.findByKeyHash(unknownHash)).thenReturn(Optional.empty());

        assertThat(authService.authenticate(unknownRawKey)).isEmpty();
        verify(valueOperations).set(unknownRedisKey, ApiKeyAuthService.INVALID_SENTINEL, ApiKeyAuthService.NEGATIVE_CACHE_TTL);

        String expiredRawKey = "shc_live_expired";
        String expiredHash = ApiKeyAuthService.sha256Hex(expiredRawKey);
        String expiredRedisKey = ApiKeyAuthService.REDIS_KEY_PREFIX + expiredHash;
        TenantApiKeyEntity expiredEntity = new TenantApiKeyEntity(
                UUID.randomUUID(),
                "smart-power",
                "old-key",
                "shc_live_expir",
                expiredHash,
                Instant.now().minus(Duration.ofMinutes(5)));

        when(valueOperations.get(expiredRedisKey)).thenReturn(null);
        when(tenantApiKeyRepository.findByKeyHash(expiredHash)).thenReturn(Optional.of(expiredEntity));

        assertThat(authService.authenticate(expiredRawKey)).isEmpty();
        verify(valueOperations).set(expiredRedisKey, ApiKeyAuthService.INVALID_SENTINEL, ApiKeyAuthService.NEGATIVE_CACHE_TTL);
    }

    @Test
    void issueKey_hashesRawKeyAndNeverStoresRawKeyInEntity() {
        when(tenantApiKeyRepository.save(any(TenantApiKeyEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        IssuedApiKeyResponse response = authService.issueKey("Smart-Power", "erp-client", null);

        ArgumentCaptor<TenantApiKeyEntity> entityCaptor = ArgumentCaptor.forClass(TenantApiKeyEntity.class);
        verify(tenantApiKeyRepository).save(entityCaptor.capture());

        TenantApiKeyEntity saved = entityCaptor.getValue();
        assertThat(response.rawApiKey()).startsWith("shc_live_");
        assertThat(response.rawApiKey()).hasSize(9 + 48);
        assertThat(saved.getKeyHash()).isEqualTo(ApiKeyAuthService.sha256Hex(response.rawApiKey()));
        assertThat(saved.getKeyHash()).isNotEqualTo(response.rawApiKey());
        assertThat(saved.getKeyPrefix()).isEqualTo(response.rawApiKey().substring(0, 14));
        assertThat(saved.getTenantId()).isEqualTo("smart-power");
    }

    @Test
    void revokeKey_marksInactiveAndEvictsRedisCache() {
        UUID keyId = UUID.randomUUID();
        String hash = ApiKeyAuthService.sha256Hex("shc_live_revoked_key");
        TenantApiKeyEntity entity = new TenantApiKeyEntity(
                keyId,
                "smart-power",
                "mobile-app",
                "shc_live_revok",
                hash,
                null);

        when(tenantApiKeyRepository.findById(keyId)).thenReturn(Optional.of(entity));
        when(tenantApiKeyRepository.save(any(TenantApiKeyEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        authService.revokeKey(keyId);

        assertThat(entity.isActive()).isFalse();
        verify(tenantApiKeyRepository).save(entity);
        verify(stringRedisTemplate).delete(ApiKeyAuthService.REDIS_KEY_PREFIX + hash);
    }
}
