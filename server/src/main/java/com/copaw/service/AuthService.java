package com.copaw.service;

import com.copaw.storage.AuthStore;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.HexFormat;

/**
 * Service for authentication operations.
 * Handles JWT token generation/validation and password hashing.
 */
@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    // Token validity: 7 days
    private static final long TOKEN_EXPIRY_MS = 7L * 24 * 3600 * 1000;

    private final AuthStore authStore;

    @Value("${copaw.auth.enabled:false}")
    private boolean authEnabledConfig;

    public AuthService(AuthStore authStore) {
        this.authStore = authStore;
    }

    /**
     * Check if authentication is enabled.
     * Authentication is enabled when COPAW_AUTH_ENABLED env var is set to true.
     *
     * @return true if authentication is enabled
     */
    public boolean isAuthEnabled() {
        String envFlag = System.getenv("COPAW_AUTH_ENABLED");
        if (envFlag != null) {
            String normalized = envFlag.strip().toLowerCase();
            return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes");
        }
        return authEnabledConfig;
    }

    /**
     * Check if any user has been registered.
     *
     * @return true if a user exists
     */
    public boolean hasRegisteredUsers() {
        Map<String, Object> authData = authStore.loadAuthData();
        Map<String, Object> user = (Map<String, Object>) authData.get("user");
        return user != null && !user.isEmpty();
    }

    /**
     * Authenticate a user with username and password.
     *
     * @param username the username
     * @param password the password
     * @return JWT token if authentication succeeds, null otherwise
     */
    public String authenticate(String username, String password) {
        if (!isAuthEnabled()) {
            return "";
        }

        Map<String, Object> authData = authStore.loadAuthData();
        Map<String, Object> user = (Map<String, Object>) authData.get("user");
        if (user == null) {
            return null;
        }

        String storedUsername = (String) user.get("username");
        if (!username.equals(storedUsername)) {
            return null;
        }

        String storedHash = (String) user.get("password_hash");
        String storedSalt = (String) user.get("password_salt");

        if (storedHash != null && storedSalt != null && verifyPassword(password, storedHash, storedSalt)) {
            return createToken(username);
        }
        return null;
    }

    /**
     * Register a new user (single-user mode).
     *
     * @param username the username
     * @param password the password
     * @return JWT token on success, null if user already exists
     */
    public String registerUser(String username, String password) {
        if (hasRegisteredUsers()) {
            return null;
        }

        Map<String, Object> authData = authStore.loadAuthData();

        // Generate salt and hash password
        String salt = generateSalt();
        String hash = hashPassword(password, salt);

        Map<String, Object> user = new HashMap<>();
        user.put("username", username);
        user.put("password_hash", hash);
        user.put("password_salt", salt);

        authData.put("user", user);

        // Ensure jwt_secret exists
        if (authData.get("jwt_secret") == null) {
            authData.put("jwt_secret", generateJwtSecret());
        }

        authStore.saveAuthData(authData);
        log.info("User '{}' registered", username);
        return createToken(username);
    }

    /**
     * Update user credentials.
     *
     * @param currentPassword current password for verification
     * @param newUsername     new username (optional)
     * @param newPassword     new password (optional)
     * @return new JWT token on success, null if verification fails
     */
    public String updateCredentials(String currentPassword, String newUsername, String newPassword) {
        Map<String, Object> authData = authStore.loadAuthData();
        Map<String, Object> user = (Map<String, Object>) authData.get("user");
        if (user == null) {
            return null;
        }

        String storedHash = (String) user.get("password_hash");
        String storedSalt = (String) user.get("password_salt");

        if (!verifyPassword(currentPassword, storedHash, storedSalt)) {
            return null;
        }

        if (newUsername != null && !newUsername.isBlank()) {
            user.put("username", newUsername.strip());
        }

        if (newPassword != null && !newPassword.isBlank()) {
            String newSalt = generateSalt();
            String newHash = hashPassword(newPassword, newSalt);
            user.put("password_hash", newHash);
            user.put("password_salt", newSalt);
            // Rotate JWT secret to invalidate all existing sessions
            authData.put("jwt_secret", generateJwtSecret());
        }

        authData.put("user", user);
        authStore.saveAuthData(authData);

        String username = (String) user.get("username");
        log.info("Credentials updated for user '{}'", username);
        return createToken(username);
    }

    /**
     * Create a JWT token for a user.
     *
     * @param username the username
     * @return JWT token
     */
    public String createToken(String username) {
        SecretKey key = getSigningKey();
        long now = System.currentTimeMillis();

        return Jwts.builder()
                .subject(username)
                .issuedAt(new Date(now))
                .expiration(new Date(now + TOKEN_EXPIRY_MS))
                .signWith(key)
                .compact();
    }

    /**
     * Verify a JWT token.
     *
     * @param token the JWT token
     * @return username if valid, null otherwise
     */
    public String verifyToken(String token) {
        try {
            SecretKey key = getSigningKey();
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            if (claims.getExpiration().before(new Date())) {
                return null;
            }

            return claims.getSubject();
        } catch (Exception e) {
            log.debug("Token verification failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Hash a password with salt using SHA-256.
     *
     * @param password the password
     * @param salt     the salt
     * @return hex-encoded hash
     */
    public String hashPassword(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = salt + password;
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Verify a password against stored hash.
     *
     * @param password    the password to verify
     * @param storedHash  the stored hash
     * @param storedSalt  the stored salt
     * @return true if password matches
     */
    public boolean verifyPassword(String password, String storedHash, String storedSalt) {
        String computedHash = hashPassword(password, storedSalt);
        return MessageDigest.isEqual(
                computedHash.getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Generate a random salt.
     *
     * @return hex-encoded salt
     */
    private String generateSalt() {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return HexFormat.of().formatHex(salt);
    }

    /**
     * Generate a random JWT secret.
     *
     * @return hex-encoded secret
     */
    private String generateJwtSecret() {
        SecureRandom random = new SecureRandom();
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        return HexFormat.of().formatHex(secret);
    }

    /**
     * Get the JWT signing key.
     *
     * @return the signing key
     */
    private SecretKey getSigningKey() {
        Map<String, Object> authData = authStore.loadAuthData();
        String secret = (String) authData.get("jwt_secret");

        if (secret == null || secret.isEmpty()) {
            secret = generateJwtSecret();
            authData.put("jwt_secret", secret);
            authStore.saveAuthData(authData);
        }

        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
