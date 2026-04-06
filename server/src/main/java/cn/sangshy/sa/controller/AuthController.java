package cn.sangshy.sa.controller;

import cn.sangshy.sa.model.auth.AuthStatusResponse;
import cn.sangshy.sa.model.auth.LoginRequest;
import cn.sangshy.sa.model.auth.LoginResponse;
import cn.sangshy.sa.model.auth.UpdateProfileRequest;
import cn.sangshy.sa.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Authentication API endpoints.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Authenticate with username and password.
     * POST /auth/login
     */
    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        if (!authService.isAuthEnabled()) {
            return LoginResponse.builder().token("").username("").build();
        }

        String token = authService.authenticate(request.getUsername(), request.getPassword());
        if (token == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        return LoginResponse.builder()
                .token(token)
                .username(request.getUsername())
                .build();
    }

    /**
     * Register the single user account (only allowed once).
     * POST /auth/register
     */
    @PostMapping("/register")
    public LoginResponse register(@RequestBody LoginRequest request) {
        if (!authService.isAuthEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authentication is not enabled");
        }

        if (authService.hasRegisteredUsers()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User already registered");
        }

        if (request.getUsername() == null || request.getUsername().isBlank() ||
            request.getPassword() == null || request.getPassword().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username and password are required");
        }

        String token = authService.registerUser(
                request.getUsername().strip(),
                request.getPassword()
        );

        if (token == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Registration failed");
        }

        return LoginResponse.builder()
                .token(token)
                .username(request.getUsername().strip())
                .build();
    }

    /**
     * Check if authentication is enabled and whether a user exists.
     * GET /auth/status
     */
    @GetMapping("/status")
    public AuthStatusResponse authStatus() {
        return AuthStatusResponse.builder()
                .enabled(authService.isAuthEnabled())
                .hasUsers(authService.hasRegisteredUsers())
                .build();
    }

    /**
     * Verify that the caller's Bearer token is still valid.
     * GET /auth/verify
     */
    @GetMapping("/verify")
    public Map<String, Object> verify(HttpServletRequest request) {
        if (!authService.isAuthEnabled()) {
            return Map.of("valid", true, "username", "");
        }

        String authHeader = request.getHeader("Authorization");
        String token = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        }

        if (token == null || token.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No token provided");
        }

        String username = authService.verifyToken(token);
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired token");
        }

        return Map.of("valid", true, "username", username);
    }

    /**
     * Update username and/or password for the authenticated user.
     * POST /auth/update-profile
     */
    @PostMapping("/update-profile")
    public LoginResponse updateProfile(
            @RequestBody UpdateProfileRequest request,
            HttpServletRequest httpRequest) {

        if (!authService.isAuthEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authentication is not enabled");
        }

        if (!authService.hasRegisteredUsers()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No user registered");
        }

        // Verify caller is authenticated
        String authHeader = httpRequest.getHeader("Authorization");
        String callerToken = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            callerToken = authHeader.substring(7);
        }

        if (callerToken == null || callerToken.isEmpty() || authService.verifyToken(callerToken) == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }

        if ((request.getNewUsername() == null || request.getNewUsername().isBlank()) &&
            (request.getNewPassword() == null || request.getNewPassword().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to update");
        }

        if (request.getNewUsername() != null && request.getNewUsername().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username cannot be empty");
        }

        if (request.getNewPassword() != null && request.getNewPassword().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password cannot be empty");
        }

        String token = authService.updateCredentials(
                request.getCurrentPassword(),
                request.getNewUsername(),
                request.getNewPassword()
        );

        if (token == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current password is incorrect");
        }

        String username = request.getNewUsername() != null ? request.getNewUsername().strip() : "";

        return LoginResponse.builder()
                .token(token)
                .username(username)
                .build();
    }
}
