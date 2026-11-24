package com.zzzeinhom.contracts.config;

import com.zzzeinhom.contracts.ai.copilot.CopilotTenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Thread-safe {@link HttpRequest.Builder} template for MCP SDK 0.10.0 {@code HttpClientSseClientTransport}.
 *
 * <p>In MCP Java SDK 0.10.0, both {@code FlowSseClient.subscribe} and
 * {@code HttpClientSseClientTransport.sendMessage} invoke {@code requestBuilder.uri(...)} directly on
 * the shared builder instance without calling {@code copy()} first. Returning a fresh per-call builder
 * on each invocation prevents cross-request state mutation while dynamically injecting the active
 * {@code X-Tenant-Id} header from {@link CopilotTenantContext} right before {@link #build()}.</p>
 */
public class TenantAwareHttpRequestBuilder implements HttpRequest.Builder {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    private static final Logger log = LoggerFactory.getLogger(TenantAwareHttpRequestBuilder.class);

    private final Consumer<HttpRequest.Builder> requestCustomizer;

    public TenantAwareHttpRequestBuilder() {
        this(TenantAwareHttpRequestBuilder::applyActiveTenantHeader);
    }

    public TenantAwareHttpRequestBuilder(Consumer<HttpRequest.Builder> requestCustomizer) {
        this.requestCustomizer = requestCustomizer != null
                ? requestCustomizer
                : TenantAwareHttpRequestBuilder::applyActiveTenantHeader;
    }

    public static void applyActiveTenantHeader(HttpRequest.Builder builder) {
        String tenantId = resolveActiveTenantId();
        if (tenantId != null && !tenantId.isBlank()) {
            builder.setHeader(TENANT_HEADER, tenantId.trim().toLowerCase(Locale.ROOT));
        }
    }

    public static String resolveActiveTenantId() {
        String tenantId = CopilotTenantContext.getTenantId();
        if (tenantId != null && !tenantId.isBlank()) {
            return tenantId;
        }
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest().getHeader(TENANT_HEADER);
        }
        return null;
    }

    private PerCallHttpRequestBuilder newPerCallBuilder() {
        HttpRequest.Builder fresh = HttpRequest.newBuilder()
                .header("Content-Type", "application/json");
        return new PerCallHttpRequestBuilder(fresh, requestCustomizer);
    }

    @Override
    public HttpRequest.Builder uri(URI uri) {
        return newPerCallBuilder().uri(uri);
    }

    @Override
    public HttpRequest.Builder expectContinue(boolean enable) {
        return newPerCallBuilder().expectContinue(enable);
    }

    @Override
    public HttpRequest.Builder version(HttpClient.Version version) {
        return newPerCallBuilder().version(version);
    }

    @Override
    public HttpRequest.Builder header(String name, String value) {
        return newPerCallBuilder().header(name, value);
    }

    @Override
    public HttpRequest.Builder headers(String... headers) {
        return newPerCallBuilder().headers(headers);
    }

    @Override
    public HttpRequest.Builder timeout(Duration duration) {
        return newPerCallBuilder().timeout(duration);
    }

    @Override
    public HttpRequest.Builder setHeader(String name, String value) {
        return newPerCallBuilder().setHeader(name, value);
    }

    @Override
    public HttpRequest.Builder GET() {
        return newPerCallBuilder().GET();
    }

    @Override
    public HttpRequest.Builder POST(HttpRequest.BodyPublisher bodyPublisher) {
        return newPerCallBuilder().POST(bodyPublisher);
    }

    @Override
    public HttpRequest.Builder PUT(HttpRequest.BodyPublisher bodyPublisher) {
        return newPerCallBuilder().PUT(bodyPublisher);
    }

    @Override
    public HttpRequest.Builder DELETE() {
        return newPerCallBuilder().DELETE();
    }

    @Override
    public HttpRequest.Builder HEAD() {
        return newPerCallBuilder().HEAD();
    }

    @Override
    public HttpRequest.Builder method(String method, HttpRequest.BodyPublisher bodyPublisher) {
        return newPerCallBuilder().method(method, bodyPublisher);
    }

    @Override
    public HttpRequest build() {
        return newPerCallBuilder().build();
    }

    @Override
    public HttpRequest.Builder copy() {
        return newPerCallBuilder();
    }

    private static final class PerCallHttpRequestBuilder implements HttpRequest.Builder {

        private final HttpRequest.Builder delegate;
        private final Consumer<HttpRequest.Builder> requestCustomizer;
        private URI requestUri;

        private PerCallHttpRequestBuilder(
                HttpRequest.Builder delegate,
                Consumer<HttpRequest.Builder> requestCustomizer) {
            this.delegate = delegate;
            this.requestCustomizer = requestCustomizer;
        }

        @Override
        public HttpRequest.Builder uri(URI uri) {
            this.requestUri = uri;
            delegate.uri(uri);
            return this;
        }

        @Override
        public HttpRequest.Builder expectContinue(boolean enable) {
            delegate.expectContinue(enable);
            return this;
        }

        @Override
        public HttpRequest.Builder version(HttpClient.Version version) {
            delegate.version(version);
            return this;
        }

        @Override
        public HttpRequest.Builder header(String name, String value) {
            delegate.header(name, value);
            return this;
        }

        @Override
        public HttpRequest.Builder headers(String... headers) {
            delegate.headers(headers);
            return this;
        }

        @Override
        public HttpRequest.Builder timeout(Duration duration) {
            delegate.timeout(duration);
            return this;
        }

        @Override
        public HttpRequest.Builder setHeader(String name, String value) {
            delegate.setHeader(name, value);
            return this;
        }

        @Override
        public HttpRequest.Builder GET() {
            delegate.GET();
            return this;
        }

        @Override
        public HttpRequest.Builder POST(HttpRequest.BodyPublisher bodyPublisher) {
            delegate.POST(bodyPublisher);
            return this;
        }

        @Override
        public HttpRequest.Builder PUT(HttpRequest.BodyPublisher bodyPublisher) {
            delegate.PUT(bodyPublisher);
            return this;
        }

        @Override
        public HttpRequest.Builder DELETE() {
            delegate.DELETE();
            return this;
        }

        @Override
        public HttpRequest.Builder HEAD() {
            delegate.HEAD();
            return this;
        }

        @Override
        public HttpRequest.Builder method(String method, HttpRequest.BodyPublisher bodyPublisher) {
            delegate.method(method, bodyPublisher);
            return this;
        }

        @Override
        public HttpRequest build() {
            requestCustomizer.accept(delegate);
            HttpRequest request = delegate.build();
            URI targetUri = requestUri != null ? requestUri : request.uri();
            request.headers().firstValue(TENANT_HEADER).ifPresentOrElse(
                    tenantHeader -> log.debug("Attaching {} '{}' to outgoing MCP request {}", TENANT_HEADER, tenantHeader, targetUri),
                    () -> log.debug("No active {} resolved for outgoing MCP request {}", TENANT_HEADER, targetUri));
            return request;
        }

        @Override
        public HttpRequest.Builder copy() {
            return new PerCallHttpRequestBuilder(delegate.copy(), requestCustomizer);
        }
    }
}
