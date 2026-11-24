package com.zzzeinhom.contracts.mcp.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class McpTenantFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String SESSION_PARAM = "sessionId";

    private static final Pattern SSE_SESSION_PATTERN =
            Pattern.compile("sessionId=([A-Za-z0-9_\\-]+)[\\s&\"'\\r\\n]");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            String rawTenantHeader = request.getHeader(TENANT_HEADER);
            String sessionId = request.getParameter(SESSION_PARAM);
            HttpServletResponse effectiveResponse = response;

            if (rawTenantHeader != null && !rawTenantHeader.isBlank()) {
                String normalizedTenant = rawTenantHeader.trim().toLowerCase(Locale.ROOT);
                McpTenantContextHolder.setTenantId(normalizedTenant);
                if (sessionId != null && !sessionId.isBlank()) {
                    McpTenantContextHolder.bindSession(sessionId, normalizedTenant);
                } else if ("GET".equalsIgnoreCase(request.getMethod())) {
                    effectiveResponse = new SseSessionCapturingResponseWrapper(response, normalizedTenant);
                }
            } else if (sessionId != null && !sessionId.isBlank()) {
                McpTenantContextHolder.resolveFromSession(sessionId)
                        .ifPresent(McpTenantContextHolder::setTenantId);
            }

            filterChain.doFilter(request, effectiveResponse);
        } finally {
            McpTenantContextHolder.clear();
        }
    }

    private static final class SseSessionCapturingResponseWrapper extends HttpServletResponseWrapper {

        private static final int MAX_INSPECT_CHARS = 2048;

        private final String tenantId;
        private final StringBuilder inspectBuffer = new StringBuilder();
        private boolean sessionCaptured = false;
        private ServletOutputStream wrappedStream;
        private PrintWriter wrappedWriter;

        private SseSessionCapturingResponseWrapper(HttpServletResponse response, String tenantId) {
            super(response);
            this.tenantId = tenantId;
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (wrappedStream == null) {
                ServletOutputStream delegate = super.getOutputStream();
                wrappedStream = new ServletOutputStream() {
                    @Override
                    public boolean isReady() {
                        return delegate.isReady();
                    }

                    @Override
                    public void setWriteListener(WriteListener writeListener) {
                        delegate.setWriteListener(writeListener);
                    }

                    @Override
                    public void write(int b) throws IOException {
                        delegate.write(b);
                        inspectText(new String(new byte[]{(byte) b}, StandardCharsets.UTF_8));
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        delegate.write(b, off, len);
                        if (len > 0) {
                            inspectText(new String(b, off, len, StandardCharsets.UTF_8));
                        }
                    }

                    @Override
                    public void flush() throws IOException {
                        delegate.flush();
                    }

                    @Override
                    public void close() throws IOException {
                        delegate.close();
                    }
                };
            }
            return wrappedStream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (wrappedWriter == null) {
                PrintWriter delegate = super.getWriter();
                wrappedWriter = new PrintWriter(new Writer() {
                    @Override
                    public void write(char[] cbuf, int off, int len) {
                        delegate.write(cbuf, off, len);
                        if (len > 0) {
                            inspectText(new String(cbuf, off, len));
                        }
                    }

                    @Override
                    public void flush() {
                        delegate.flush();
                    }

                    @Override
                    public void close() {
                        delegate.close();
                    }
                }, false);
            }
            return wrappedWriter;
        }

        private void inspectText(String chunk) {
            if (sessionCaptured || chunk == null || chunk.isEmpty()) {
                return;
            }
            int remaining = MAX_INSPECT_CHARS - inspectBuffer.length();
            if (remaining <= 0) {
                sessionCaptured = true;
                inspectBuffer.setLength(0);
                return;
            }
            inspectBuffer.append(chunk, 0, Math.min(chunk.length(), remaining));
            Matcher matcher = SSE_SESSION_PATTERN.matcher(inspectBuffer);
            if (matcher.find()) {
                McpTenantContextHolder.bindSession(matcher.group(1), tenantId);
                sessionCaptured = true;
                inspectBuffer.setLength(0);
            } else if (inspectBuffer.length() >= MAX_INSPECT_CHARS) {
                sessionCaptured = true;
                inspectBuffer.setLength(0);
            }
        }
    }
}
