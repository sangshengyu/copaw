package com.copaw.security;

import com.copaw.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

/**
 * JWT Authentication Filter.
 * Validates Bearer tokens on protected routes.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JwtAuthFilter implements Filter {
    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/auth/login",
            "/auth/status",
            "/auth/register",
            "/version"
    );

    private static final Set<String> PUBLIC_PREFIXES = Set.of(
            "/assets/",
            "/logo.png",
            "/copaw-symbol.svg"
    );

    private final AuthService authService;
    private final ObjectMapper objectMapper;

    @Autowired
    public JwtAuthFilter(AuthService authService, ObjectMapper objectMapper) {
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        log.info("JwtAuthFilter initialized");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        if (shouldSkipAuth(httpRequest)) {
            chain.doFilter(request, response);
            return;
        }

        String token = extractToken(httpRequest);
        if (token == null) {
            sendUnauthorized(httpResponse, "Not authenticated");
            return;
        }

        String username = authService.verifyToken(token);
        if (username == null) {
            sendUnauthorized(httpResponse, "Invalid or expired token");
            return;
        }

        // Store username in request attribute for later use
        httpRequest.setAttribute("user", username);
        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
        log.info("JwtAuthFilter destroyed");
    }

    /**
     * Check if authentication should be skipped for this request.
     */
    private boolean shouldSkipAuth(HttpServletRequest request) {
        // Skip if auth not enabled or no users registered
        if (!authService.isAuthEnabled() || !authService.hasRegisteredUsers()) {
            return true;
        }

        String path = request.getRequestURI();

        // Allow OPTIONS requests (CORS preflight)
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // Check public paths
        if (PUBLIC_PATHS.contains(path)) {
            return true;
        }

        // Check public prefixes (static assets)
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }

        // Protect all API routes (except static assets and public paths already checked above)
        // Static assets are already handled by PUBLIC_PREFIXES check
        // Public paths are already handled by PUBLIC_PATHS check
        // So remaining paths need authentication

        // Allow localhost requests without auth (CLI runs locally)
        String remoteAddr = request.getRemoteAddr();
        return "127.0.0.1".equals(remoteAddr) || "0:0:0:0:0:0:0:1".equals(remoteAddr);
    }

    /**
     * Extract Bearer token from request.
     */
    private String extractToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        // Check for token in query params (for WebSocket connections)
        String connection = request.getHeader("Connection");
        if (connection != null && connection.toLowerCase().contains("upgrade")) {
            return request.getParameter("token");
        }

        // Also check query param for regular requests
        return request.getParameter("token");
    }

    /**
     * Send 401 Unauthorized response.
     */
    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String json = objectMapper.writeValueAsString(
                java.util.Map.of("detail", message)
        );
        response.getWriter().write(json);
    }
}
