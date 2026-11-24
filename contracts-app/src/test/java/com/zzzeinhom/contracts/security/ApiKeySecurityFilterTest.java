package com.zzzeinhom.contracts.security;

import com.zzzeinhom.contracts.config.CopilotConfig;
import com.zzzeinhom.contracts.controller.ApiKeyAdminController;
import com.zzzeinhom.contracts.controller.CopilotController;
import com.zzzeinhom.contracts.dto.CopilotChatResponse;
import com.zzzeinhom.contracts.dto.IssuedApiKeyResponse;
import com.zzzeinhom.contracts.service.OperationsCopilotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = {CopilotController.class, ApiKeyAdminController.class},
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = CopilotConfig.class))
@Import(SecurityConfig.class)
@TestPropertySource(properties = "app.security.admin-key=test-admin-secret")
class ApiKeySecurityFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OperationsCopilotService operationsCopilotService;

    @MockitoBean
    private ApiKeyAuthService apiKeyAuthService;

    @Test
    void requestWithoutApiKey_returns401UnauthorizedProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/copilot/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"c1\",\"message\":\"Hello\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"));

        verifyNoInteractions(operationsCopilotService);
    }

    @Test
    void validApiKey_usesAuthenticatedTenantAndIgnoresSpoofedTenantHeaderAndClearsContext() throws Exception {
        String rawKey = "shc_live_valid_tenant_key";
        when(apiKeyAuthService.authenticate(rawKey)).thenReturn(Optional.of("smart-power"));
        when(operationsCopilotService.chat(eq("smart-power"), eq("c1"), eq("Hello")))
                .thenReturn(new CopilotChatResponse("c1", "Verified reply", Instant.now()));

        mockMvc.perform(post("/api/v1/copilot/chat")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, rawKey)
                        .header("X-Tenant-Id", "spoofed-evil-tenant")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"c1\",\"message\":\"Hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value("c1"))
                .andExpect(jsonPath("$.reply").value("Verified reply"));

        verify(operationsCopilotService).chat("smart-power", "c1", "Hello");
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    void adminEndpoint_requiresValidAdminKey() throws Exception {
        mockMvc.perform(post("/api/v1/admin/api-keys")
                        .header(ApiKeyAuthenticationFilter.API_KEY_HEADER, "shc_live_valid_tenant_key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"smart-power\",\"keyName\":\"erp\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        IssuedApiKeyResponse issued = new IssuedApiKeyResponse(
                UUID.randomUUID(),
                "smart-power",
                "erp",
                "shc_live_12345",
                "shc_live_1234567890abcdef",
                Instant.now(),
                null);
        when(apiKeyAuthService.issueKey("smart-power", "erp", null)).thenReturn(issued);

        mockMvc.perform(post("/api/v1/admin/api-keys")
                        .header(AdminApiKeyFilter.ADMIN_KEY_HEADER, "test-admin-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"smart-power\",\"keyName\":\"erp\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tenantId").value("smart-power"))
                .andExpect(jsonPath("$.rawApiKey").value("shc_live_1234567890abcdef"));
    }
}
