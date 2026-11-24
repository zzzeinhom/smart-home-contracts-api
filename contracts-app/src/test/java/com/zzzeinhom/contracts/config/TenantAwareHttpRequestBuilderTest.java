package com.zzzeinhom.contracts.config;

import com.zzzeinhom.contracts.ai.copilot.CopilotTenantContext;
import com.zzzeinhom.contracts.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URI;
import java.net.http.HttpRequest;

import static org.assertj.core.api.Assertions.assertThat;

class TenantAwareHttpRequestBuilderTest {

    @AfterEach
    void tearDown() {
        CopilotTenantContext.clear();
        TenantContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void build_attachesActiveTenantHeaderAndIsolatesPerCallBuilders() {
        TenantAwareHttpRequestBuilder sharedTemplate = new TenantAwareHttpRequestBuilder();

        CopilotTenantContext.setTenantId("Smart-Power");
        HttpRequest firstRequest = sharedTemplate
                .uri(URI.create("http://localhost:8081/mcp/message?sessionId=s1"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        CopilotTenantContext.setTenantId("Other-Tenant");
        HttpRequest secondRequest = sharedTemplate
                .uri(URI.create("http://localhost:8081/mcp/message?sessionId=s2"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        CopilotTenantContext.clear();
        HttpRequest startupHandshakeRequest = sharedTemplate
                .uri(URI.create("http://localhost:8081/sse"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();

        assertThat(firstRequest.headers().firstValue(TenantAwareHttpRequestBuilder.TENANT_HEADER))
                .contains("smart-power");
        assertThat(firstRequest.headers().firstValue("Content-Type"))
                .contains("application/json");

        assertThat(secondRequest.headers().firstValue(TenantAwareHttpRequestBuilder.TENANT_HEADER))
                .contains("other-tenant");
        assertThat(secondRequest.headers().firstValue("Accept")).isEmpty();

        assertThat(startupHandshakeRequest.headers().firstValue(TenantAwareHttpRequestBuilder.TENANT_HEADER))
                .isEmpty();
        assertThat(startupHandshakeRequest.headers().firstValue("Accept"))
                .contains("text/event-stream");
    }

    @Test
    void build_fallsBackToAuthenticatedTenantContextAndIgnoresUntrustedServletHeader() {
        TenantAwareHttpRequestBuilder sharedTemplate = new TenantAwareHttpRequestBuilder();
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.addHeader(TenantAwareHttpRequestBuilder.TENANT_HEADER, "spoofed-tenant");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));
        TenantContext.setTenantId("Cairo-Homes");

        HttpRequest outgoingRequest = sharedTemplate
                .uri(URI.create("http://localhost:8081/mcp/message?sessionId=s3"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        assertThat(outgoingRequest.headers().firstValue(TenantAwareHttpRequestBuilder.TENANT_HEADER))
                .contains("cairo-homes");
    }
}
