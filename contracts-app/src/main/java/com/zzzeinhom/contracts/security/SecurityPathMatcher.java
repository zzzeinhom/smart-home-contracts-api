package com.zzzeinhom.contracts.security;

import jakarta.servlet.http.HttpServletRequest;

final class SecurityPathMatcher {

    private SecurityPathMatcher() {
    }

    static boolean isAdminPath(HttpServletRequest request) {
        String path = resolveApplicationPath(request);
        return path.equals("/api/v1/admin") || path.startsWith("/api/v1/admin/");
    }

    private static String resolveApplicationPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        if (servletPath != null && !servletPath.isEmpty()) {
            return servletPath;
        }
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && requestUri != null && requestUri.startsWith(contextPath)) {
            return requestUri.substring(contextPath.length());
        }
        return requestUri != null ? requestUri : "";
    }
}
