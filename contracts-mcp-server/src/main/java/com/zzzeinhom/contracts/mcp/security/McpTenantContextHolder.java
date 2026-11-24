package com.zzzeinhom.contracts.mcp.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class McpTenantContextHolder {

    static final int MAX_SESSIONS = 10_000;
    private static final Duration SESSION_TTL = Duration.ofHours(12);

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();
    private static final ConcurrentMap<String, SessionTenantEntry> SESSION_TENANTS = new ConcurrentHashMap<>();

    private McpTenantContextHolder() {
    }

    public static void setTenantId(String tenantId) {
        String normalized = normalizeTenantId(tenantId);
        if (normalized == null) {
            CURRENT_TENANT.remove();
            return;
        }
        CURRENT_TENANT.set(normalized);
    }

    public static void bindSession(String sessionId, String tenantId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String normalizedTenant = normalizeTenantId(tenantId);
        if (normalizedTenant == null) {
            return;
        }

        Instant now = Instant.now();
        String trimmedSessionId = sessionId.trim();
        if (!SESSION_TENANTS.containsKey(trimmedSessionId) && SESSION_TENANTS.size() >= MAX_SESSIONS) {
            evictIfNeeded(now);
        }
        SESSION_TENANTS.put(trimmedSessionId, new SessionTenantEntry(normalizedTenant, now));
    }

    public static Optional<String> resolveFromSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        String trimmedSessionId = sessionId.trim();
        SessionTenantEntry entry = SESSION_TENANTS.get(trimmedSessionId);
        if (entry == null) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        if (isExpired(entry, now)) {
            SESSION_TENANTS.remove(trimmedSessionId, entry);
            return Optional.empty();
        }
        SESSION_TENANTS.replace(trimmedSessionId, entry, new SessionTenantEntry(entry.tenantId(), now));
        return Optional.of(entry.tenantId());
    }

    public static String getTenantId() {
        return CURRENT_TENANT.get();
    }

    public static String requireTenantId() {
        String tenantId = CURRENT_TENANT.get();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("Missing X-Tenant-Id header in MCP request context");
        }
        return tenantId;
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }

    public static void clearSessions() {
        SESSION_TENANTS.clear();
    }

    static int sessionCount() {
        return SESSION_TENANTS.size();
    }

    private static void evictIfNeeded(Instant now) {
        SESSION_TENANTS.entrySet().removeIf(e -> isExpired(e.getValue(), now));
        while (SESSION_TENANTS.size() >= MAX_SESSIONS) {
            Optional<Map.Entry<String, SessionTenantEntry>> oldest = SESSION_TENANTS.entrySet().stream()
                    .min(Comparator.comparing(e -> e.getValue().lastAccessedAt()));
            if (oldest.isEmpty()) {
                break;
            }
            SESSION_TENANTS.remove(oldest.get().getKey(), oldest.get().getValue());
        }
    }

    private static boolean isExpired(SessionTenantEntry entry, Instant now) {
        return entry.lastAccessedAt().plus(SESSION_TTL).isBefore(now);
    }

    private static String normalizeTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return null;
        }
        return tenantId.trim().toLowerCase(Locale.ROOT);
    }

    private record SessionTenantEntry(String tenantId, Instant lastAccessedAt) {
    }
}
