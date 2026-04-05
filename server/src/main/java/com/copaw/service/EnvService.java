package com.copaw.service;

import com.copaw.model.common.EnvVar;
import com.copaw.storage.EnvStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Service for managing environment variables.
 */
@Service
public class EnvService {
    private static final Logger log = LoggerFactory.getLogger(EnvService.class);

    private final EnvStore envStore;

    public EnvService(EnvStore envStore) {
        this.envStore = envStore;
    }

    /**
     * List all environment variables.
     */
    public List<EnvVar> listEnvVars() {
        Map<String, String> envVars = envStore.loadEnvVars();
        List<EnvVar> result = new ArrayList<>();
        
        for (Map.Entry<String, String> entry : envVars.entrySet()) {
            result.add(EnvVar.builder()
                .key(entry.getKey())
                .value(entry.getValue())
                .build());
        }
        
        // Sort by key
        result.sort(Comparator.comparing(EnvVar::getKey));
        
        return result;
    }

    /**
     * Batch save environment variables (full replacement).
     */
    public List<EnvVar> batchSaveEnvVars(Map<String, String> envVars) {
        // Validate keys
        for (String key : envVars.keySet()) {
            if (key == null || key.trim().isEmpty()) {
                throw new IllegalArgumentException("Key cannot be empty");
            }
        }
        
        // Clean keys (trim whitespace)
        Map<String, String> cleaned = new java.util.HashMap<>();
        for (Map.Entry<String, String> entry : envVars.entrySet()) {
            cleaned.put(entry.getKey().trim(), entry.getValue());
        }
        
        envStore.saveEnvVars(cleaned);
        
        return listEnvVars();
    }

    /**
     * Delete an environment variable.
     */
    public List<EnvVar> deleteEnvVar(String key) {
        Map<String, String> envVars = envStore.loadEnvVars();
        
        if (!envVars.containsKey(key)) {
            throw new IllegalArgumentException("Env var '" + key + "' not found");
        }
        
        envStore.deleteEnvVar(key);
        
        return listEnvVars();
    }

    /**
     * Get a specific environment variable.
     */
    public EnvVar getEnvVar(String key) {
        String value = envStore.getEnvVar(key);
        if (value == null) {
            return null;
        }
        return EnvVar.builder()
            .key(key)
            .value(value)
            .build();
    }

    /**
     * Set a single environment variable.
     */
    public EnvVar setEnvVar(String key, String value) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("Key cannot be empty");
        }
        
        envStore.setEnvVar(key.trim(), value);
        
        return EnvVar.builder()
            .key(key.trim())
            .value(value)
            .build();
    }
}
