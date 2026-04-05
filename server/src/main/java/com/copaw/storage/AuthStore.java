package com.copaw.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Store for managing authentication data.
 * Reads and writes auth.json in the secret directory.
 */
@Component
public class AuthStore {
    private static final Logger log = LoggerFactory.getLogger(AuthStore.class);

    private final CoPawDataDir dataDir;
    private final JsonFileStore<Object> jsonFileStore;

    public AuthStore(CoPawDataDir dataDir, JsonFileStore<Object> jsonFileStore) {
        this.dataDir = dataDir;
        this.jsonFileStore = jsonFileStore;
    }

    /**
     * Load authentication data.
     *
     * @return map of auth data
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> loadAuthData() {
        Path authPath = dataDir.getAuthPath();
        if (!Files.exists(authPath)) {
            return new HashMap<>();
        }

        try {
            String content = Files.readString(authPath);
            return jsonFileStore.getObjectMapper().readValue(content, Map.class);
        } catch (IOException e) {
            log.warn("Failed to load auth data: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Save authentication data.
     *
     * @param authData the auth data to save
     */
    public void saveAuthData(Map<String, Object> authData) {
        Path authPath = dataDir.getAuthPath();
        try {
            Files.createDirectories(authPath.getParent());
            String json = jsonFileStore.getObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(authData);
            Files.writeString(authPath, json);
        } catch (IOException e) {
            log.error("Failed to save auth data: {}", e.getMessage());
            throw new RuntimeException("Failed to save auth data", e);
        }
    }

    /**
     * Get the password hash.
     *
     * @return the password hash, or null if not set
     */
    public String getPasswordHash() {
        return (String) loadAuthData().get("password_hash");
    }

    /**
     * Set the password hash.
     *
     * @param hash the password hash
     */
    public void setPasswordHash(String hash) {
        Map<String, Object> authData = loadAuthData();
        authData.put("password_hash", hash);
        saveAuthData(authData);
    }

    /**
     * Check if authentication is enabled.
     *
     * @return true if authentication is enabled
     */
    public boolean isAuthEnabled() {
        Map<String, Object> authData = loadAuthData();
        return authData.containsKey("password_hash") && authData.get("password_hash") != null;
    }

    /**
     * Get the JWT secret key.
     *
     * @return the JWT secret, or null if not set
     */
    public String getJwtSecret() {
        return (String) loadAuthData().get("jwt_secret");
    }

    /**
     * Set the JWT secret key.
     *
     * @param secret the JWT secret
     */
    public void setJwtSecret(String secret) {
        Map<String, Object> authData = loadAuthData();
        authData.put("jwt_secret", secret);
        saveAuthData(authData);
    }

    /**
     * Initialize auth data with default values.
     */
    public void initializeIfNeeded() {
        if (!Files.exists(dataDir.getAuthPath())) {
            Map<String, Object> authData = new HashMap<>();
            // Don't set password_hash - auth is disabled by default
            saveAuthData(authData);
        }
    }
}
