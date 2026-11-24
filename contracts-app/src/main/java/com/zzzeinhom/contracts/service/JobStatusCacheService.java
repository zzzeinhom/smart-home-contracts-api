package com.zzzeinhom.contracts.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzzeinhom.contracts.domain.enums.ExtractionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
public class JobStatusCacheService {

    private static final Logger log = LoggerFactory.getLogger(JobStatusCacheService.class);
    private static final Duration STATUS_TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public JobStatusCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void putStatus(String tenantId, String jobId, ExtractionStatus status, String errorMessage) {
        try {
            String payload = objectMapper.writeValueAsString(new JobStatusSnapshot(jobId, status, errorMessage));
            redisTemplate.opsForValue().set(buildKey(tenantId, jobId), payload, STATUS_TTL);
        } catch (Exception ex) {
            log.warn("Failed to write job status snapshot to Redis for tenant={} jobId={}: {}",
                    tenantId, jobId, ex.getMessage());
        }
    }

    public Optional<JobStatusSnapshot> getStatus(String tenantId, String jobId) {
        try {
            String raw = redisTemplate.opsForValue().get(buildKey(tenantId, jobId));
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(raw, JobStatusSnapshot.class));
        } catch (Exception ex) {
            log.warn("Failed to read job status snapshot from Redis for tenant={} jobId={}: {}",
                    tenantId, jobId, ex.getMessage());
            return Optional.empty();
        }
    }

    private static String buildKey(String tenantId, String jobId) {
        return "tenant:" + tenantId + ":job:" + jobId;
    }

    public record JobStatusSnapshot(
            String jobId,
            ExtractionStatus status,
            String errorMessage
    ) {}
}
