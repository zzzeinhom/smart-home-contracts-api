package com.zzzeinhom.contracts.ai.copilot;

import java.util.Locale;

public final class CopilotTenantContext {

    private static final InheritableThreadLocal<String> CURRENT_TENANT = new InheritableThreadLocal<>();

    private CopilotTenantContext() {
    }

    public static void setTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            CURRENT_TENANT.remove();
            return;
        }
        CURRENT_TENANT.set(tenantId.trim().toLowerCase(Locale.ROOT));
    }

    public static String getTenantId() {
        return CURRENT_TENANT.get();
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
