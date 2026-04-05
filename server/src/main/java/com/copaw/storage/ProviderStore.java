package com.copaw.storage;

import com.copaw.model.provider.ActiveModelsInfo;
import com.copaw.model.provider.ModelInfo;
import com.copaw.model.provider.ModelSlotConfig;
import com.copaw.model.provider.ProviderInfo;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Store for managing provider configurations.
 * Reads and writes providers configuration files.
 */
@Component
public class ProviderStore {
    private static final Logger log = LoggerFactory.getLogger(ProviderStore.class);

    private final CoPawDataDir dataDir;
    private final JsonFileStore<Object> jsonFileStore;

    public ProviderStore(CoPawDataDir dataDir, JsonFileStore<Object> jsonFileStore) {
        this.dataDir = dataDir;
        this.jsonFileStore = jsonFileStore;
    }

    /**
     * Load all provider configurations.
     *
     * @return map of provider ID to provider info
     */
    public Map<String, ProviderInfo> loadAllProviders() {
        Map<String, ProviderInfo> providers = new HashMap<>();

        // Load builtin providers
        Path builtinDir = dataDir.getBuiltinProvidersDir();
        if (Files.exists(builtinDir)) {
            loadProvidersFromDir(builtinDir, providers, false);
        }

        // Load custom providers
        Path customDir = dataDir.getCustomProvidersDir();
        if (Files.exists(customDir)) {
            loadProvidersFromDir(customDir, providers, true);
        }

        return providers;
    }

    private void loadProvidersFromDir(Path dir, Map<String, ProviderInfo> providers, boolean isCustom) {
        try {
            Files.list(dir)
                    .filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p);
                            ProviderInfo info = jsonFileStore.getObjectMapper()
                                    .readValue(content, ProviderInfo.class);
                            info.setIsCustom(isCustom);
                            providers.put(info.getId(), info);
                        } catch (IOException e) {
                            log.warn("Failed to load provider from {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to list providers in {}: {}", dir, e.getMessage());
        }
    }

    /**
     * Load a specific provider by ID.
     *
     * @param providerId the provider ID
     * @return the provider info, or null if not found
     */
    public ProviderInfo loadProvider(String providerId) {
        // Try builtin first
        Path builtinPath = dataDir.getBuiltinProvidersDir().resolve(providerId + ".json");
        if (Files.exists(builtinPath)) {
            try {
                String content = Files.readString(builtinPath);
                ProviderInfo info = jsonFileStore.getObjectMapper().readValue(content, ProviderInfo.class);
                info.setIsCustom(false);
                return info;
            } catch (IOException e) {
                log.warn("Failed to load builtin provider {}: {}", providerId, e.getMessage());
            }
        }

        // Try custom
        Path customPath = dataDir.getCustomProvidersDir().resolve(providerId + ".json");
        if (Files.exists(customPath)) {
            try {
                String content = Files.readString(customPath);
                ProviderInfo info = jsonFileStore.getObjectMapper().readValue(content, ProviderInfo.class);
                info.setIsCustom(true);
                return info;
            } catch (IOException e) {
                log.warn("Failed to load custom provider {}: {}", providerId, e.getMessage());
            }
        }

        return null;
    }

    /**
     * Save a provider configuration.
     *
     * @param provider the provider info to save
     */
    public void saveProvider(ProviderInfo provider) {
        Path targetDir = Boolean.TRUE.equals(provider.getIsCustom()) 
                ? dataDir.getCustomProvidersDir() 
                : dataDir.getBuiltinProvidersDir();
        
        try {
            Files.createDirectories(targetDir);
            Path targetPath = targetDir.resolve(provider.getId() + ".json");
            String json = jsonFileStore.getObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(provider);
            Files.writeString(targetPath, json);
        } catch (IOException e) {
            log.error("Failed to save provider {}: {}", provider.getId(), e.getMessage());
            throw new RuntimeException("Failed to save provider", e);
        }
    }

    /**
     * Delete a custom provider.
     *
     * @param providerId the provider ID to delete
     * @return true if deleted successfully
     */
    public boolean deleteProvider(String providerId) {
        Path customPath = dataDir.getCustomProvidersDir().resolve(providerId + ".json");
        if (Files.exists(customPath)) {
            try {
                Files.delete(customPath);
                return true;
            } catch (IOException e) {
                log.error("Failed to delete provider {}: {}", providerId, e.getMessage());
                return false;
            }
        }
        return false;
    }

    /**
     * Load active models configuration.
     *
     * @return the active models info
     */
    public ActiveModelsInfo loadActiveModels() {
        Path activeLlmPath = dataDir.getActiveLlmPath();
        if (!Files.exists(activeLlmPath)) {
            return ActiveModelsInfo.builder().build();
        }

        try {
            String content = Files.readString(activeLlmPath);
            return jsonFileStore.getObjectMapper().readValue(content, ActiveModelsInfo.class);
        } catch (IOException e) {
            log.warn("Failed to load active models: {}", e.getMessage());
            return ActiveModelsInfo.builder().build();
        }
    }

    /**
     * Save active models configuration.
     *
     * @param activeModels the active models info to save
     */
    public void saveActiveModels(ActiveModelsInfo activeModels) {
        Path activeLlmPath = dataDir.getActiveLlmPath();
        try {
            Files.createDirectories(activeLlmPath.getParent());
            String json = jsonFileStore.getObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(activeModels);
            Files.writeString(activeLlmPath, json);
        } catch (IOException e) {
            log.error("Failed to save active models: {}", e.getMessage());
            throw new RuntimeException("Failed to save active models", e);
        }
    }

    /**
     * Get the active LLM configuration.
     *
     * @return the active LLM model slot config, or null if not set
     */
    public ModelSlotConfig getActiveLlm() {
        ActiveModelsInfo activeModels = loadActiveModels();
        return activeModels.getActiveLlm();
    }

    /**
     * Set the active LLM configuration.
     *
     * @param providerId the provider ID
     * @param modelId    the model ID
     */
    public void setActiveLlm(String providerId, String modelId) {
        ModelSlotConfig config = ModelSlotConfig.builder()
                .providerId(providerId)
                .model(modelId)
                .build();
        ActiveModelsInfo activeModels = ActiveModelsInfo.builder()
                .activeLlm(config)
                .build();
        saveActiveModels(activeModels);
    }
}
