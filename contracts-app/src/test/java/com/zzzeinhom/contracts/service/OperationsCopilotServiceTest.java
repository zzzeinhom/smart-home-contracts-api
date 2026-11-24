package com.zzzeinhom.contracts.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperationsCopilotServiceTest {

    @Mock
    private ToolCallback delegateToolCallback;

    @Test
    void wrapWithFailureGuard_abortsImmediatelyOnMissingTenantHeaderError() {
        when(delegateToolCallback.getToolDefinition()).thenReturn(
                DefaultToolDefinition.builder()
                        .name("aggregateProcurementBom")
                        .description("Aggregates procurement BoM")
                        .inputSchema("{}")
                        .build());
        when(delegateToolCallback.call(anyString()))
                .thenReturn("Error: Missing X-Tenant-Id header in MCP request context");

        ToolCallback[] guarded = OperationsCopilotService.wrapWithFailureGuard(
                new ToolCallback[]{delegateToolCallback});

        assertThatThrownBy(() -> guarded[0].call("{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MCP tool execution aborted: Error: Missing X-Tenant-Id header in MCP request context");

        verify(delegateToolCallback, times(1)).call("{}");
    }

    @Test
    void wrapWithFailureGuard_abortsOnSecondToolFailureInSameTurn() {
        when(delegateToolCallback.getToolDefinition()).thenReturn(
                DefaultToolDefinition.builder()
                        .name("getPendingInstallments")
                        .description("Fetches pending installments")
                        .inputSchema("{}")
                        .build());
        when(delegateToolCallback.call(anyString()))
                .thenReturn("{\"isError\":true,\"content\":\"DB timeout\"}");

        ToolCallback[] guarded = OperationsCopilotService.wrapWithFailureGuard(
                new ToolCallback[]{delegateToolCallback});

        assertThat(guarded[0].call("{}")).isEqualTo("{\"isError\":true,\"content\":\"DB timeout\"}");

        assertThatThrownBy(() -> guarded[0].call("{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MCP tool execution aborted: {\"isError\":true,\"content\":\"DB timeout\"}");

        verify(delegateToolCallback, times(2)).call("{}");
    }
}
