package com.zzzeinhom.contracts.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ApiKeyAuthService apiKeyAuthService;
    private final ObjectMapper objectMapper;
    private final String adminKey;

    public SecurityConfig(
            ApiKeyAuthService apiKeyAuthService,
            ObjectMapper objectMapper,
            @Value("${app.security.admin-key:}") String adminKey) {
        this.apiKeyAuthService = apiKeyAuthService;
        this.objectMapper = objectMapper;
        this.adminKey = adminKey;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/**").hasRole("TENANT")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeProblem(request, response, HttpStatus.UNAUTHORIZED, "Unauthorized",
                                        "Missing or invalid API key"))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeProblem(request, response, HttpStatus.FORBIDDEN, "Forbidden",
                                        "Insufficient permissions to access this resource")))
                .addFilterBefore(new AdminApiKeyFilter(adminKey, objectMapper), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new ApiKeyAuthenticationFilter(apiKeyAuthService), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private void writeProblem(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String title,
            String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
