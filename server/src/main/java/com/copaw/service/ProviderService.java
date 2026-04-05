package com.copaw.service;

import com.copaw.model.provider.*;
import com.copaw.model.provider.dto.*;
import com.copaw.storage.ProviderStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;

/**
 * Service for managing LLM providers and models.
 */
@Service
public class ProviderService {
    private static final Logger log = LoggerFactory.getLogger(ProviderService.class);

    private final ProviderStore providerStore;
    private final Map<String, ProviderInfo> builtinProviders = new HashMap<>();
    private final Map<String, ProviderInfo> customProviders = new HashMap<>();
    private ActiveModelsInfo activeModels;

    public ProviderService(ProviderStore providerStore) {
        this.providerStore = providerStore;
    }

    @PostConstruct
    public void init() {
        // Initialize builtin providers
        initBuiltinProviders();
        
        // Load from storage
        loadFromStorage();
    }

    private void initBuiltinProviders() {
        // DashScope provider with default models
        List<ModelInfo> dashscopeModels = Arrays.asList(
            ModelInfo.builder()
                .id("qwen3-max")
                .name("Qwen3 Max")
                .supportsImage(false)
                .supportsVideo(false)
                .probeSource("documentation")
                .build(),
            ModelInfo.builder()
                .id("qwen3-235b-a22b-thinking-2507")
                .name("Qwen3 235B A22B Thinking")
                .supportsImage(false)
                .supportsVideo(false)
                .probeSource("documentation")
                .build(),
            ModelInfo.builder()
                .id("deepseek-v3.2")
                .name("DeepSeek-V3.2")
                .supportsImage(false)
                .supportsVideo(false)
                .probeSource("documentation")
                .build()
        );

        ProviderInfo dashscope = ProviderInfo.builder()
            .id("dashscope")
            .name("DashScope")
            .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
            .apiKeyPrefix("sk")
            .models(dashscopeModels)
            .freezeUrl(true)
            .requireApiKey(true)
            .supportModelDiscovery(false)
            .supportConnectionCheck(true)
            .isCustom(false)
            .build();

        builtinProviders.put(dashscope.getId(), dashscope);
    }

    private void loadFromStorage() {
        // Load all providers from storage
        Map<String, ProviderInfo> storedProviders = providerStore.loadAllProviders();
        
        // Update builtin providers with stored config
        for (Map.Entry<String, ProviderInfo> entry : storedProviders.entrySet()) {
            String providerId = entry.getKey();
            ProviderInfo stored = entry.getValue();
            
            if (Boolean.TRUE.equals(stored.getIsCustom())) {
                customProviders.put(providerId, stored);
            } else if (builtinProviders.containsKey(providerId)) {
                // Merge stored config with builtin
                ProviderInfo builtin = builtinProviders.get(providerId);
                mergeProviderConfig(builtin, stored);
            }
        }

        // Load active models
        activeModels = providerStore.loadActiveModels();
    }

    private void mergeProviderConfig(ProviderInfo target, ProviderInfo source) {
        if (source.getApiKey() != null) {
            target.setApiKey(source.getApiKey());
        }
        if (source.getBaseUrl() != null && !Boolean.TRUE.equals(target.getFreezeUrl())) {
            target.setBaseUrl(source.getBaseUrl());
        }
        if (source.getChatModel() != null) {
            target.setChatModel(source.getChatModel());
        }
        if (source.getExtraModels() != null) {
            target.setExtraModels(source.getExtraModels());
        }
        if (source.getGenerateKwargs() != null) {
            target.setGenerateKwargs(source.getGenerateKwargs());
        }
    }

    /**
     * List all providers with masked API keys.
     */
    public List<ProviderInfo> listProviders() {
        List<ProviderInfo> allProviders = new ArrayList<>();
        
        // Add builtin providers
        for (ProviderInfo provider : builtinProviders.values()) {
            allProviders.add(maskApiKey(provider));
        }
        
        // Add custom providers
        for (ProviderInfo provider : customProviders.values()) {
            allProviders.add(maskApiKey(provider));
        }
        
        return allProviders;
    }

    /**
     * Get a specific provider.
     */
    public ProviderInfo getProvider(String providerId) {
        ProviderInfo provider = builtinProviders.get(providerId);
        if (provider == null) {
            provider = customProviders.get(providerId);
        }
        return provider != null ? maskApiKey(provider) : null;
    }

    /**
     * Get raw provider (without masking).
     */
    private ProviderInfo getRawProvider(String providerId) {
        ProviderInfo provider = builtinProviders.get(providerId);
        if (provider == null) {
            provider = customProviders.get(providerId);
        }
        return provider;
    }

    /**
     * Mask API key for display.
     * Format: first 2-3 chars + **** + last 4 chars, or full mask if shorter than 8 chars.
     */
    private ProviderInfo maskApiKey(ProviderInfo provider) {
        if (provider == null || provider.getApiKey() == null || provider.getApiKey().isEmpty()) {
            return provider;
        }

        ProviderInfo masked = ProviderInfo.builder()
            .id(provider.getId())
            .name(provider.getName())
            .baseUrl(provider.getBaseUrl())
            .chatModel(provider.getChatModel())
            .models(provider.getModels())
            .extraModels(provider.getExtraModels())
            .apiKeyPrefix(provider.getApiKeyPrefix())
            .isLocal(provider.getIsLocal())
            .freezeUrl(provider.getFreezeUrl())
            .requireApiKey(provider.getRequireApiKey())
            .isCustom(provider.getIsCustom())
            .supportModelDiscovery(provider.getSupportModelDiscovery())
            .supportConnectionCheck(provider.getSupportConnectionCheck())
            .generateKwargs(provider.getGenerateKwargs())
            .build();

        String apiKey = provider.getApiKey();
        if (apiKey.length() < 8) {
            masked.setApiKey("****");
        } else {
            int prefixLen = Math.min(3, apiKey.length() - 7);
            masked.setApiKey(apiKey.substring(0, prefixLen) + "****" + apiKey.substring(apiKey.length() - 4));
        }

        return masked;
    }

    /**
     * Update provider configuration.
     */
    public ProviderInfo updateProvider(String providerId, ProviderConfigRequest request) {
        ProviderInfo provider = getRawProvider(providerId);
        if (provider == null) {
            return null;
        }

        if (request.getApiKey() != null) {
            provider.setApiKey(request.getApiKey());
        }
        if (request.getBaseUrl() != null && !Boolean.TRUE.equals(provider.getFreezeUrl())) {
            provider.setBaseUrl(request.getBaseUrl());
        }
        if (request.getChatModel() != null) {
            provider.setChatModel(request.getChatModel());
        }
        if (request.getGenerateKwargs() != null) {
            provider.setGenerateKwargs(request.getGenerateKwargs());
        }

        // Save to storage
        providerStore.saveProvider(provider);

        return maskApiKey(provider);
    }

    /**
     * Add a custom provider.
     */
    public ProviderInfo addCustomProvider(CreateCustomProviderRequest request) {
        String resolvedId = resolveCustomProviderId(request.getId());

        ProviderInfo provider = ProviderInfo.builder()
            .id(resolvedId)
            .name(request.getName())
            .baseUrl(request.getDefaultBaseUrl())
            .apiKeyPrefix(request.getApiKeyPrefix())
            .chatModel(request.getChatModel())
            .extraModels(request.getModels() != null ? request.getModels() : new ArrayList<>())
            .isCustom(true)
            .supportConnectionCheck(false)
            .supportModelDiscovery(false)
            .requireApiKey(true)
            .build();

        customProviders.put(resolvedId, provider);
        providerStore.saveProvider(provider);

        return maskApiKey(provider);
    }

    private String resolveCustomProviderId(String baseId) {
        String resolvedId = baseId;
        if (builtinProviders.containsKey(resolvedId)) {
            resolvedId = resolvedId + "-custom";
        }
        while (builtinProviders.containsKey(resolvedId) || customProviders.containsKey(resolvedId)) {
            resolvedId = resolvedId + "-new";
        }
        return resolvedId;
    }

    /**
     * Remove a custom provider.
     */
    public boolean removeCustomProvider(String providerId) {
        if (!customProviders.containsKey(providerId)) {
            return false;
        }
        customProviders.remove(providerId);
        return providerStore.deleteProvider(providerId);
    }

    /**
     * Test provider connection.
     * TODO: Implement actual connection test by calling the provider API.
     */
    public TestConnectionResponse testProvider(String providerId, TestProviderRequest request) {
        ProviderInfo provider = getRawProvider(providerId);
        if (provider == null) {
            return TestConnectionResponse.builder()
                .success(false)
                .message("Provider '" + providerId + "' not found")
                .build();
        }

        // TODO: Implement actual connection test
        // For now, return a stub response
        String apiKey = request != null && request.getApiKey() != null 
            ? request.getApiKey() 
            : provider.getApiKey();
        
        if (apiKey == null || apiKey.isEmpty()) {
            return TestConnectionResponse.builder()
                .success(false)
                .message("API key is required")
                .build();
        }

        // Stub: assume success for now
        return TestConnectionResponse.builder()
            .success(true)
            .message("Connection successful (stub)")
            .build();
    }

    /**
     * Discover models from a provider.
     * TODO: Implement actual model discovery from provider API.
     */
    public DiscoverModelsResponse discoverModels(String providerId, DiscoverModelsRequest request) {
        ProviderInfo provider = getRawProvider(providerId);
        if (provider == null) {
            return DiscoverModelsResponse.builder()
                .success(false)
                .message("Provider '" + providerId + "' not found")
                .build();
        }

        // Update provider config if provided
        if (request != null) {
            if (request.getApiKey() != null) {
                provider.setApiKey(request.getApiKey());
            }
            if (request.getBaseUrl() != null && !Boolean.TRUE.equals(provider.getFreezeUrl())) {
                provider.setBaseUrl(request.getBaseUrl());
            }
        }

        // TODO: Implement actual model discovery
        // For now, return empty list
        return DiscoverModelsResponse.builder()
            .success(true)
            .models(new ArrayList<>())
            .message("Model discovery not implemented yet")
            .addedCount(0)
            .build();
    }

    /**
     * Add a model to a provider.
     */
    public ProviderInfo addModel(String providerId, AddModelRequest request) {
        ProviderInfo provider = getRawProvider(providerId);
        if (provider == null) {
            throw new IllegalArgumentException("Provider '" + providerId + "' not found");
        }

        ModelInfo model = ModelInfo.builder()
            .id(request.getId())
            .name(request.getName())
            .build();

        provider.getExtraModels().add(model);
        providerStore.saveProvider(provider);

        return maskApiKey(provider);
    }

    /**
     * Remove a model from a provider.
     */
    public ProviderInfo removeModel(String providerId, String modelId) {
        ProviderInfo provider = getRawProvider(providerId);
        if (provider == null) {
            throw new IllegalArgumentException("Provider '" + providerId + "' not found");
        }

        boolean removed = provider.getExtraModels().removeIf(m -> m.getId().equals(modelId));
        if (!removed) {
            // Also check builtin models
            removed = provider.getModels().removeIf(m -> m.getId().equals(modelId));
        }

        if (!removed) {
            throw new IllegalArgumentException("Model '" + modelId + "' not found in provider '" + providerId + "'");
        }

        providerStore.saveProvider(provider);
        return maskApiKey(provider);
    }

    /**
     * Update model configuration.
     */
    public ProviderInfo updateModelConfig(String providerId, String modelId, ModelConfigRequest request) {
        ProviderInfo provider = getRawProvider(providerId);
        if (provider == null) {
            throw new IllegalArgumentException("Provider '" + providerId + "' not found");
        }

        // Find model in extra_models or models
        ModelInfo model = findModel(provider, modelId);
        if (model == null) {
            throw new IllegalArgumentException("Model '" + modelId + "' not found in provider '" + providerId + "'");
        }

        if (request.getGenerateKwargs() != null) {
            model.setGenerateKwargs(request.getGenerateKwargs());
        }

        providerStore.saveProvider(provider);
        return maskApiKey(provider);
    }

    private ModelInfo findModel(ProviderInfo provider, String modelId) {
        for (ModelInfo model : provider.getExtraModels()) {
            if (model.getId().equals(modelId)) {
                return model;
            }
        }
        for (ModelInfo model : provider.getModels()) {
            if (model.getId().equals(modelId)) {
                return model;
            }
        }
        return null;
    }

    /**
     * Test a specific model.
     */
    public TestConnectionResponse testModel(String providerId, TestModelRequest request) {
        ProviderInfo provider = getRawProvider(providerId);
        if (provider == null) {
            return TestConnectionResponse.builder()
                .success(false)
                .message("Provider '" + providerId + "' not found")
                .build();
        }

        ModelInfo model = findModel(provider, request.getModelId());
        if (model == null) {
            return TestConnectionResponse.builder()
                .success(false)
                .message("Model '" + request.getModelId() + "' not found in provider '" + providerId + "'")
                .build();
        }

        // TODO: Implement actual model test
        return TestConnectionResponse.builder()
            .success(true)
            .message("Model connection successful (stub)")
            .build();
    }

    /**
     * Probe model multimodal capabilities.
     * TODO: Implement actual probing.
     */
    public ProbeMultimodalResponse probeMultimodal(String providerId, String modelId) {
        ProviderInfo provider = getRawProvider(providerId);
        if (provider == null) {
            throw new IllegalArgumentException("Provider '" + providerId + "' not found");
        }

        ModelInfo model = findModel(provider, modelId);
        if (model == null) {
            throw new IllegalArgumentException("Model '" + modelId + "' not found in provider '" + providerId + "'");
        }

        // TODO: Implement actual multimodal probing
        // For now, return default values
        ProbeMultimodalResponse response = ProbeMultimodalResponse.builder()
            .supportsImage(false)
            .supportsVideo(false)
            .supportsMultimodal(false)
            .imageMessage("Not probed yet")
            .videoMessage("Not probed yet")
            .build();

        // Update model with probe result
        model.setSupportsImage(response.getSupportsImage());
        model.setSupportsVideo(response.getSupportsVideo());
        model.setSupportsMultimodal(response.getSupportsMultimodal());
        model.setProbeSource("probed");

        providerStore.saveProvider(provider);

        return response;
    }

    /**
     * Get active models.
     */
    public ActiveModelsInfo getActiveModels() {
        if (activeModels == null || activeModels.getActiveLlm() == null) {
            return ActiveModelsInfo.builder().build();
        }
        return activeModels;
    }

    /**
     * Set active model.
     */
    public ActiveModelsInfo setActiveModel(String providerId, String modelId) {
        ProviderInfo provider = getRawProvider(providerId);
        if (provider == null) {
            throw new IllegalArgumentException("Provider '" + providerId + "' not found");
        }

        if (findModel(provider, modelId) == null) {
            throw new IllegalArgumentException("Model '" + modelId + "' not found in provider '" + providerId + "'");
        }

        ModelSlotConfig slot = ModelSlotConfig.builder()
            .providerId(providerId)
            .model(modelId)
            .build();

        activeModels = ActiveModelsInfo.builder()
            .activeLlm(slot)
            .build();

        providerStore.saveActiveModels(activeModels);

        return activeModels;
    }

    /**
     * Check if provider has a specific model.
     */
    public boolean hasModel(String providerId, String modelId) {
        ProviderInfo provider = getRawProvider(providerId);
        if (provider == null) {
            return false;
        }
        return findModel(provider, modelId) != null;
    }
}
