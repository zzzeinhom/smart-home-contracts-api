package com.zzzeinhom.contracts.config;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CopilotConfigTest {

    @Mock
    private McpSyncClient mcpSyncClient;

    @Mock
    private ObjectProvider<List<McpSyncClient>> syncMcpClientsProvider;

    @Test
    void unprefixedMcpToolCallbackProvider_exposesRawShortToolNamesWithoutClientPrefix() {
        McpSchema.JsonSchema schema = new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null);
        McpSchema.Tool bomTool = new McpSchema.Tool(
                "aggregateProcurementBom",
                "Aggregates procurement BoM across projects",
                schema);

        when(syncMcpClientsProvider.stream()).thenReturn(Stream.of(List.of(mcpSyncClient)));
        when(mcpSyncClient.getClientInfo())
                .thenReturn(new McpSchema.Implementation("smart-home-copilot-client - operations-mcp", "1.0.0"));
        when(mcpSyncClient.listTools())
                .thenReturn(new McpSchema.ListToolsResult(List.of(bomTool), null));
        when(mcpSyncClient.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("{\"items\":[]}")),
                        false));

        CopilotConfig config = new CopilotConfig();
        ToolCallbackProvider provider = config.unprefixedMcpToolCallbackProvider(syncMcpClientsProvider);

        ToolCallback[] callbacks = provider.getToolCallbacks();
        assertThat(callbacks).hasSize(1);
        assertThat(callbacks[0].getToolDefinition().name()).isEqualTo("aggregateProcurementBom");
        assertThat(callbacks[0].getToolDefinition().description()).isEqualTo("Aggregates procurement BoM across projects");

        String result = callbacks[0].call("{\"status\":\"APPROVED\"}");
        assertThat(result).contains("items");

        ArgumentCaptor<McpSchema.CallToolRequest> requestCaptor =
                ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(mcpSyncClient).callTool(requestCaptor.capture());
        assertThat(requestCaptor.getValue().name()).isEqualTo("aggregateProcurementBom");
    }
}
