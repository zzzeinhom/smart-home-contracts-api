package com.zzzeinhom.contracts.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

public class AdminApiKeyFilter extends OncePerRequestFilter {

    public static final String ADMIN_KEY_HEADER = "X-Admin-Key";

    private final String expectedAdminKey;
    private final ObjectMapper objectMapper;

    public AdminApiKeyFilter(String expectedAdminKey, ObjectMapper objectMapper) {
        this.expectedAdminKey = expectedAdminKey;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !SecurityPathMatcher.isAdminPath(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (expectedAdminKey == null || expectedAdminKey.isBlank()) {
            writeServiceUnavailable(request, response);
            return;
        }

        try {
            String providedAdminKey = request.getHeader(ADMIN_KEY_HEADER);
            if (providedAdminKey != null && !providedAdminKey.isBlank()
                    && constantTimeEquals(expectedAdminKey.trim(), providedAdminKey.trim())) {
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        "admin",
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void writeServiceUnavailable(HttpServletRequest request, HttpServletResponse response) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Admin API key is not configured");
        problem.setTitle("Service Unavailable");
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), problem);
    }

    private static boolean constantTimeEquals(String expected, String provided) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] expectedHash = digest.digest(expected.getBytes(StandardCharsets.UTF_8));
            byte[] providedHash = digest.digest(provided.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(expectedHash, providedHash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", ex);
        }
    }
}
