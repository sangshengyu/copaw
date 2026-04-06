package cn.sangshy.sa.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Store for managing environment variables.
 * Reads and writes envs.json in the secret directory.
 */
@Component
public class EnvStore {
    private static final Logger log = LoggerFactory.getLogger(EnvStore.class);

    private final SADataDir dataDir;
    private final JsonFileStore<Object> jsonFileStore;

    public EnvStore(SADataDir dataDir, JsonFileStore<Object> jsonFileStore) {
        this.dataDir = dataDir;
        this.jsonFileStore = jsonFileStore;
    }

    /**
     * Load all environment variables.
     *
     * @return map of environment variable name to value
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> loadEnvVars() {
        Path envsPath = dataDir.getEnvsPath();
        if (!Files.exists(envsPath)) {
            return new HashMap<>();
        }

        try {
            String content = Files.readString(envsPath);
            return jsonFileStore.getObjectMapper().readValue(content, Map.class);
        } catch (IOException e) {
            log.warn("Failed to load env vars: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Save all environment variables.
     *
     * @param envVars the environment variables to save
     */
    public void saveEnvVars(Map<String, String> envVars) {
        Path envsPath = dataDir.getEnvsPath();
        try {
            Files.createDirectories(envsPath.getParent());
            String json = jsonFileStore.getObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(envVars);
            Files.writeString(envsPath, json);
        } catch (IOException e) {
            log.error("Failed to save env vars: {}", e.getMessage());
            throw new RuntimeException("Failed to save env vars", e);
        }
    }

    /**
     * Get a specific environment variable.
     *
     * @param key the environment variable name
     * @return the value, or null if not found
     */
    public String getEnvVar(String key) {
        return loadEnvVars().get(key);
    }

    /**
     * Set a specific environment variable.
     *
     * @param key   the environment variable name
     * @param value the value
     */
    public void setEnvVar(String key, String value) {
        Map<String, String> envVars = loadEnvVars();
        envVars.put(key, value);
        saveEnvVars(envVars);
    }

    /**
     * Delete a specific environment variable.
     *
     * @param key the environment variable name
     * @return true if deleted, false if not found
     */
    public boolean deleteEnvVar(String key) {
        Map<String, String> envVars = loadEnvVars();
        if (envVars.containsKey(key)) {
            envVars.remove(key);
            saveEnvVars(envVars);
            return true;
        }
        return false;
    }
}
