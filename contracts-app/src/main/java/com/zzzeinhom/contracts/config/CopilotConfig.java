package com.zzzeinhom.contracts.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.mcp.client.autoconfigure.NamedClientMcpTransport;
import org.springframework.ai.mcp.client.autoconfigure.properties.McpClientCommonProperties;
import org.springframework.ai.mcp.client.autoconfigure.properties.McpSseClientProperties;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Configuration
@EnableConfigurationProperties({McpSseClientProperties.class, McpClientCommonProperties.class})
public class CopilotConfig {

    private static final int CHAT_MEMORY_MAX_MESSAGES = 12;

    @Bean
    public ChatMemory chatMemory(JdbcChatMemoryRepository jdbcChatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(jdbcChatMemoryRepository)
                .maxMessages(CHAT_MEMORY_MAX_MESSAGES)
                .build();
    }

    @Bean
    public Consumer<HttpRequest.Builder> mcpTenantHttpRequestCustomizer() {
        return TenantAwareHttpRequestBuilder::applyActiveTenantHeader;
    }

    @Bean
    @ConditionalOnProperty(prefix = "spring.ai.mcp.client", name = "enabled", havingValue = "true", matchIfMissing = true)
    public List<NamedClientMcpTransport> mcpHttpClientTransports(
            McpSseClientProperties sseProperties,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            Consumer<HttpRequest.Builder> mcpTenantHttpRequestCustomizer) {
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        TenantAwareHttpRequestBuilder tenantAwareRequestBuilder =
                new TenantAwareHttpRequestBuilder(mcpTenantHttpRequestCustomizer);

        List<NamedClientMcpTransport> sseTransports = new ArrayList<>();
        for (Map.Entry<String, McpSseClientProperties.SseParameters> entry : sseProperties.getConnections().entrySet()) {
            String baseUrl = entry.getValue().url();
            String sseEndpoint = entry.getValue().sseEndpoint() != null
                    ? entry.getValue().sseEndpoint()
                    : "/sse";
            HttpClientSseClientTransport transport = HttpClientSseClientTransport.builder(baseUrl)
                    .sseEndpoint(sseEndpoint)
                    .clientBuilder(HttpClient.newBuilder())
                    .requestBuilder(tenantAwareRequestBuilder)
                    .objectMapper(objectMapper)
                    .build();
            sseTransports.add(new NamedClientMcpTransport(entry.getKey(), transport));
        }
        return sseTransports;
    }

    /**
     * Spring AI 1.0.6's {@code SyncMcpToolCallback} always prepends the sanitized client/connection name
     * via {@code McpToolUtils.prefixedToolName(...)} (prior to Spring AI 1.1's {@code McpToolNamePrefixGenerator}).
     * This {@code @Primary} provider exposes each MCP tool using its clean raw {@code tool.name()} directly
     * (e.g., {@code aggregateProcurementBom}) while delegating execution to {@link SyncMcpToolCallback}.
     */
    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "spring.ai.mcp.client", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ToolCallbackProvider unprefixedMcpToolCallbackProvider(
            ObjectProvider<List<McpSyncClient>> syncMcpClientsProvider) {
        return () -> {
            List<McpSyncClient> clients = syncMcpClientsProvider.stream()
                    .flatMap(List::stream)
                    .toList();
            Map<String, ToolCallback> callbacksByName = new LinkedHashMap<>();
            for (McpSyncClient client : clients) {
                McpSchema.ListToolsResult listToolsResult = client.listTools();
                if (listToolsResult == null || listToolsResult.tools() == null) {
                    continue;
                }
                for (McpSchema.Tool tool : listToolsResult.tools()) {
                    if (tool == null || tool.name() == null || tool.name().isBlank()) {
                        continue;
                    }
                    SyncMcpToolCallback delegate = new SyncMcpToolCallback(client, tool);
                    callbacksByName.putIfAbsent(tool.name(), new UnprefixedToolCallback(tool.name(), delegate));
                }
            }
            return callbacksByName.values().toArray(ToolCallback[]::new);
        };
    }

    private static final class UnprefixedToolCallback implements ToolCallback {

        private final ToolDefinition toolDefinition;
        private final ToolCallback delegate;

        private UnprefixedToolCallback(String rawToolName, ToolCallback delegate) {
            ToolDefinition delegateDefinition = delegate.getToolDefinition();
            this.toolDefinition = DefaultToolDefinition.builder()
                    .name(rawToolName)
                    .description(delegateDefinition.description())
                    .inputSchema(delegateDefinition.inputSchema())
                    .build();
            this.delegate = delegate;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return toolDefinition;
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return delegate.call(toolInput);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return delegate.call(toolInput, toolContext);
        }
    }
}
