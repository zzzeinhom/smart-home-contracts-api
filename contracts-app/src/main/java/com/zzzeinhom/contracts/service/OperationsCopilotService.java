package com.zzzeinhom.contracts.service;

import com.zzzeinhom.contracts.ai.copilot.CopilotTenantContext;
import com.zzzeinhom.contracts.ai.copilot.TenantIsolationAdvisor;
import com.zzzeinhom.contracts.dto.CopilotChatResponse;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Service
public class OperationsCopilotService {

    private static final int MEMORY_ADVISOR_ORDER = Ordered.HIGHEST_PRECEDENCE + 1000;
    private static final int LOGGER_ADVISOR_ORDER = Ordered.HIGHEST_PRECEDENCE + 2000;

    private final ChatClient chatClient;
    private final ToolCallbackProvider mcpToolCallbackProvider;
    private final ChatMemory chatMemory;
    private final Resource systemPromptResource;

    public OperationsCopilotService(
            ChatClient.Builder chatClientBuilder,
            ToolCallbackProvider mcpToolCallbackProvider,
            ChatMemory chatMemory,
            TenantIsolationAdvisor tenantIsolationAdvisor,
            @Value("classpath:prompts/copilot-system.st") Resource systemPromptResource) {
        this.mcpToolCallbackProvider = mcpToolCallbackProvider;
        this.chatMemory = chatMemory;
        this.systemPromptResource = systemPromptResource;
        this.chatClient = chatClientBuilder
                .defaultAdvisors(
                        tenantIsolationAdvisor,
                        MessageChatMemoryAdvisor.builder(chatMemory)
                                .order(MEMORY_ADVISOR_ORDER)
                                .build(),
                        SimpleLoggerAdvisor.builder()
                                .order(LOGGER_ADVISOR_ORDER)
                                .build())
                .build();
    }

    public CopilotChatResponse chat(String tenantId, String conversationId, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("User message must not be blank.");
        }

        String normalizedTenantId = normalizeTenantId(tenantId);
        String effectiveConversationId = (conversationId == null || conversationId.isBlank())
                ? UUID.randomUUID().toString()
                : conversationId.trim();

        CopilotTenantContext.setTenantId(normalizedTenantId);
        try {
            ToolCallback[] guardedCallbacks = wrapWithFailureGuard(mcpToolCallbackProvider.getToolCallbacks());
            String reply = chatClient.prompt()
                    .system(systemPromptResource)
                    .user(userMessage)
                    .toolCallbacks(guardedCallbacks)
                    .advisors(spec -> spec
                            .param(TenantIsolationAdvisor.TENANT_ID_KEY, normalizedTenantId)
                            .param(ChatMemory.CONVERSATION_ID, effectiveConversationId))
                    .call()
                    .content();

            return new CopilotChatResponse(
                    effectiveConversationId,
                    reply != null ? reply : "",
                    Instant.now());
        } finally {
            CopilotTenantContext.clear();
        }
    }

    static ToolCallback[] wrapWithFailureGuard(ToolCallback[] rawCallbacks) {
        if (rawCallbacks == null || rawCallbacks.length == 0) {
            return new ToolCallback[0];
        }
        Map<String, Integer> failureCounts = new ConcurrentHashMap<>();
        ToolCallback[] guarded = new ToolCallback[rawCallbacks.length];
        for (int i = 0; i < rawCallbacks.length; i++) {
            ToolCallback rawCallback = rawCallbacks[i];
            guarded[i] = rawCallback == null ? null : new FailureGuardedToolCallback(rawCallback, failureCounts);
        }
        return guarded;
    }

    private static final class FailureGuardedToolCallback implements ToolCallback {

        private static final int MAX_FAILURES_PER_TOOL = 2;

        private final ToolCallback delegate;
        private final Map<String, Integer> failureCounts;

        private FailureGuardedToolCallback(ToolCallback delegate, Map<String, Integer> failureCounts) {
            this.delegate = delegate;
            this.failureCounts = failureCounts;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return executeGuarded(() -> delegate.call(toolInput));
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return executeGuarded(() -> delegate.call(toolInput, toolContext));
        }

        private String executeGuarded(Supplier<String> invocation) {
            String toolName = delegate.getToolDefinition().name();
            String result;
            try {
                result = invocation.get();
            } catch (RuntimeException ex) {
                String message = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                if (message.contains("Missing X-Tenant-Id")
                        || failureCounts.merge(toolName, 1, Integer::sum) >= MAX_FAILURES_PER_TOOL) {
                    throw new IllegalStateException("MCP tool execution aborted: " + message, ex);
                }
                throw ex;
            }

            if (result != null && result.contains("Missing X-Tenant-Id")) {
                throw new IllegalStateException("MCP tool execution aborted: " + result);
            }
            if (isErrorPayload(result)
                    && failureCounts.merge(toolName, 1, Integer::sum) >= MAX_FAILURES_PER_TOOL) {
                throw new IllegalStateException("MCP tool execution aborted: " + result);
            }
            return result;
        }

        private static boolean isErrorPayload(String result) {
            if (result == null || result.isBlank()) {
                return false;
            }
            String lower = result.toLowerCase(Locale.ROOT);
            return result.contains("\"isError\":true")
                    || result.contains("\"isError\": true")
                    || result.contains("IllegalStateException")
                    || lower.contains("error executing tool");
        }
    }

    public void clearConversation(String tenantId, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank.");
        }
        String normalizedTenantId = normalizeTenantId(tenantId);
        String trimmedConversationId = conversationId.trim();
        String prefix = normalizedTenantId + ":";
        String namespacedKey = trimmedConversationId.startsWith(prefix)
                ? trimmedConversationId
                : prefix + trimmedConversationId;
        chatMemory.clear(namespacedKey);
    }

    private static String normalizeTenantId(String tenantId) {
        return (tenantId == null || tenantId.isBlank())
                ? "default"
                : tenantId.trim().toLowerCase(Locale.ROOT);
    }
}
