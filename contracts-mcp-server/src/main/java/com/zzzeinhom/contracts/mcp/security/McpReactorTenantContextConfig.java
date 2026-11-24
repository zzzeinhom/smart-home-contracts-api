package com.zzzeinhom.contracts.mcp.security;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Schedulers;

@Configuration
public class McpReactorTenantContextConfig {

    public static final String HOOK_KEY = "mcp-tenant-context";

    @PostConstruct
    public void registerScheduleHook() {
        Schedulers.onScheduleHook(HOOK_KEY, McpReactorTenantContextConfig::propagateTenantContext);
    }

    @PreDestroy
    public void resetScheduleHook() {
        Schedulers.resetOnScheduleHook(HOOK_KEY);
    }

    public static Runnable propagateTenantContext(Runnable runnable) {
        String capturedTenantId = McpTenantContextHolder.getTenantId();
        if (capturedTenantId == null || capturedTenantId.isBlank()) {
            return runnable;
        }
        return () -> {
            String previousTenantId = McpTenantContextHolder.getTenantId();
            McpTenantContextHolder.setTenantId(capturedTenantId);
            try {
                runnable.run();
            } finally {
                if (previousTenantId == null || previousTenantId.isBlank()) {
                    McpTenantContextHolder.clear();
                } else {
                    McpTenantContextHolder.setTenantId(previousTenantId);
                }
            }
        };
    }
}
