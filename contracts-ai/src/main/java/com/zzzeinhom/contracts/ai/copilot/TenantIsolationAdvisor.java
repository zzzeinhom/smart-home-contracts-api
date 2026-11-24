package com.zzzeinhom.contracts.ai.copilot;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class TenantIsolationAdvisor implements CallAdvisor {

    public static final String TENANT_ID_KEY = "tenant_id";
    public static final String DEFAULT_CONVERSATION_ID = "default";
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        if (chatClientRequest == null) {
            throw new IllegalArgumentException("ChatClientRequest must not be null.");
        }
        if (callAdvisorChain == null) {
            throw new IllegalArgumentException("CallAdvisorChain must not be null.");
        }

        Map<String, Object> existingContext = chatClientRequest.context();
        Object rawTenantObj = existingContext != null ? existingContext.get(TENANT_ID_KEY) : null;
        if (!(rawTenantObj instanceof String rawTenantId) || rawTenantId.isBlank()) {
            throw new IllegalStateException(
                    "Tenant isolation violation: required advisor context parameter '" + TENANT_ID_KEY + "' is missing or blank.");
        }

        String normalizedTenantId = rawTenantId.trim().toLowerCase(Locale.ROOT);
        Object rawConversationObj = existingContext.get(ChatMemory.CONVERSATION_ID);
        String rawConversationId = (rawConversationObj instanceof String conversation && !conversation.isBlank())
                ? conversation.trim()
                : DEFAULT_CONVERSATION_ID;

        String prefix = normalizedTenantId + ":";
        String namespacedConversationId = rawConversationId.startsWith(prefix)
                ? rawConversationId
                : prefix + rawConversationId;

        Map<String, Object> updatedContext = new HashMap<>(existingContext);
        updatedContext.put(TENANT_ID_KEY, normalizedTenantId);
        updatedContext.put(ChatMemory.CONVERSATION_ID, namespacedConversationId);

        ChatClientRequest mutatedRequest = chatClientRequest.mutate()
                .context(updatedContext)
                .build();

        return callAdvisorChain.nextCall(mutatedRequest);
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
