package com.zzzeinhom.contracts.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";

    private final ApiKeyAuthService apiKeyAuthService;

    public ApiKeyAuthenticationFilter(ApiKeyAuthService apiKeyAuthService) {
        this.apiKeyAuthService = apiKeyAuthService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SecurityPathMatcher.isAdminPath(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            String rawApiKey = request.getHeader(API_KEY_HEADER);
            if (rawApiKey != null && !rawApiKey.isBlank()) {
                Optional<String> tenantIdOpt = apiKeyAuthService.authenticate(rawApiKey);
                if (tenantIdOpt.isPresent()) {
                    String tenantId = tenantIdOpt.get();
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            tenantId,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_TENANT")));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    TenantContext.setTenantId(tenantId);
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
