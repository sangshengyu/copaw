package com.copaw.service;

import com.copaw.model.agent.AgentProfileConfig;
import com.copaw.model.provider.*;
import com.copaw.model.provider.dto.*;
import com.copaw.storage.AgentConfigStore;
import com.copaw.storage.ProviderStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Service for managing LLM providers and models.
 */
@Service
public class ProviderService {
    private static final Logger log = LoggerFactory.getLogger(ProviderService.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // HTTP URL for providers that accept external video URLs.
    private static final String PROBE_VIDEO_URL =
        "https://help-static-aliyun-doc.aliyuncs.com/file-manage-files/zh-CN/20241115/cqqkru/1.mp4";

    // 32x32 red PNG (96 bytes), used as minimal probe image.
    private static final String PROBE_IMAGE_B64 =
        "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAAJ0lEQVR42u3NsQkAAAjA" +
        "sP7/tF7hIASyp6lTCQQCgUAgEAgEgi/BAjLD/C5w/SM9AAAAAElFTkSuQmCC";

    // 64x64 solid-blue H.264 MP4 (10 frames @ 10fps, ~1.8 KB)
    private static final String PROBE_VIDEO_B64 =
        "AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAAIZnJlZQAAA2Vt" +
        "ZGF0AAACrgYF//+q3EXpvebZSLeWLNgg2SPu73gyNjQgLSBjb3JlIDE2NCBy" +
        "MzEwOCAzMWUxOWY5IC0gSC4yNjQvTVBFRy00IEFWQyBjb2RlYyAtIENvcHls" +
        "ZWZ0IDIwMDMtMjAyMyAtIGh0dHA6Ly93d3cudmlkZW9sYW4ub3JnL3gyNjQu" +
        "aHRtbCAtIG9wdGlvbnM6IGNhYmFjPTEgcmVmPTMgZGVibG9jaz0xOjA6MCBh" +
        "bmFseXNlPTB4MzoweDExMyBtZT1oZXggc3VibWU9NyBwc3k9MSBwc3lfcmQ9" +
        "MS4wMDowLjAwIG1peGVkX3JlZj0xIG1lX3JhbmdlPTE2IGNocm9tYV9tZT0x" +
        "IHRyZWxsaXM9MSA4eDhkY3Q9MSBjcW09MCBkZWFkem9uZT0yMSwxMSBmYXN0" +
        "X3Bza2lwPTEgY2hyb21hX3FwX29mZnNldD0tMiB0aHJlYWRzPTIgbG9va2Fo" +
        "ZWFkX3RocmVhZHM9MSBzbGljZWRfdGhyZWFkcz0wIG5yPTAgZGVjaW1hdGU9" +
        "MSBpbnRlcmxhY2VkPTAgYmx1cmF5X2NvbXBhdD0wIGNvbnN0cmFpbmVkX2lu" +
        "dHJhPTAgYmZyYW1lcz0zIGJfcHlyYW1pZD0yIGJfYWRhcHQ9MSBiX2JpYXM9" +
        "MCBkaXJlY3Q9MSB3ZWlnaHRiPTEgb3Blbl9nb3A9MCB3ZWlnaHRwPTIga2V5" +
        "aW50PTI1MCBrZXlpbnRfbWluPTEwIHNjZW5lY3V0PTQwIGludHJhX3JlZnJl" +
        "c2g9MCByY19sb29rYWhlYWQ9NDAgcmM9Y3JmIG1idHJlZT0xIGNyZj0yMy4w" +
        "IHFjb21wPTAuNjAgcXBtaW49MCBxcG1heD02OSBxcHN0ZXA9NCBpcF9yYXRp" +
        "bz0xLjQwIGFxPTE6MS4wMACAAAAAJ2WIhAAR//7n4/wKbYEB8Tpk2PtANbXc" +
        "qLo1x7YozakvH3bhD2xGfwAAAApBmiRsQQ/+qlfeAAAACEGeQniHfwW9AAAA" +
        "CAGeYXRDfwd8AAAACAGeY2pDfwd9AAAAEEGaaEmoQWiZTAh3//6pnTUAAAAK" +
        "QZ6GRREsO/8FvQAAAAgBnqV0Q38HfQAAAAgBnqdqQ38HfAAAABBBmqlJqEFs" +
        "mUwIb//+p4+IAAADoG1vb3YAAABsbXZoZAAAAAAAAAAAAAAAAAAAA+gAAAPo" +
        "AAEAAAEAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAA" +
        "AAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAALLdHJhawAA" +
        "AFx0a2hkAAAAAwAAAAAAAAAAAAAAAQAAAAAAAAPoAAAAAAAAAAAAAAAAAAAA" +
        "AAABAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAQAAAAABAAAAAQAAA" +
        "AAAAJGVkdHMAAAAcZWxzdAAAAAAAAAABAAAD6AAACAAAAQAAAAACQ21kaWEA" +
        "AAAgbWRoZAAAAAAAAAAAAAAAAAAAKAAAACgAVcQAAAAAAC1oZGxyAAAAAAAA" +
        "AAB2aWRlAAAAAAAAAAAAAAAAVmlkZW9IYW5kbGVyAAAAAe5taW5mAAAAFHZt" +
        "aGQAAAABAAAAAAAAAAAAAAAkZGluZgAAABxkcmVmAAAAAAAAAAEAAAAMdXJs" +
        "IAAAAAEAAAGuc3RibAAAAK5zdHNkAAAAAAAAAAEAAACeYXZjMQAAAAAAAAAB" +
        "AAAAAAAAAAAAAAAAAAAAAABAAEAASAAAAEgAAAAAAAAAAQAAAAAAAAAAAAAA" +
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAABj//wAAADRhdmNDAWQACv/hABdnZAAK" +
        "rNlEJoQAAAMABAAAAwBQPEiWWAEABmjr48siwP34+AAAAAAUYnRydAAAAAAA" +
        "AE4gAAAa6AAAABhzdHRzAAAAAAAAAAEAAAAKAAAEAAAAABRzdHNzAAAAAAAA" +
        "AAEAAAABAAAAYGN0dHMAAAAAAAAACgAAAAEAAAgAAAAAAQAAFAAAAAABAAAI" +
        "AAAAAAEAAAAAAAAAAQAABAAAAAABAAAUAAAAAAEAAAgAAAAAAQAAAAAAAAAB" +
        "AAAEAAAAAAEAAAgAAAAAHHN0c2MAAAAAAAAAAQAAAAEAAAAKAAAAAQAAADxz" +
        "dHN6AAAAAAAAAAAAAAAKAAAC3QAAAA4AAAAMAAAADAAAAAwAAAAUAAAADgAA" +
        "AAwAAAAMAAAAFAAAABRzdGNvAAAAAAAAAAEAAAAwAAAAYXVkdGEAAABZbWV0" +
        "YQAAAAAAAAAhaGRscgAAAAAAAAAAbWRpcmFwcGwAAAAAAAAAAAAAAAAsaWxz" +
        "dAAAACSpdG9vAAAAHGRhdGEAAAABAAAAAExhdmY2MS43LjEwMA==";

    private final ProviderStore providerStore;
    private final AgentConfigStore agentConfigStore;
    private final Map<String, ProviderInfo> builtinProviders = new HashMap<>();
    private final Map<String, ProviderInfo> customProviders = new HashMap<>();
    private ActiveModelsInfo activeModels;

    public ProviderService(ProviderStore providerStore, AgentConfigStore agentConfigStore) {
        this.providerStore = providerStore;
        this.agentConfigStore = agentConfigStore;
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
            // DashScope OpenAI-compatible endpoint
            // This endpoint supports /models, /chat/completions, etc.
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
    public ProviderInfo getRawProvider(String providerId) {
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
     * Test provider connection by calling the /models endpoint.
     */
    public TestConnectionResponse testProvider(String providerId, TestProviderRequest request) {
        ProviderInfo provider = getRawProvider(providerId);
        if (provider == null) {
            return TestConnectionResponse.builder()
                .success(false)
                .message("Provider '" + providerId + "' not found")
                .build();
        }
    
        // Get API key: prefer request param, fallback to stored config
        String apiKey = request != null && request.getApiKey() != null 
            ? request.getApiKey() 
            : provider.getApiKey();
    
        if (apiKey == null || apiKey.isEmpty()) {
            return TestConnectionResponse.builder()
                .success(false)
                .message("API key is required")
                .build();
        }
    
        // Get base URL: prefer request param, fallback to stored config
        String baseUrl = request != null && request.getBaseUrl() != null 
            ? request.getBaseUrl() 
            : provider.getBaseUrl();
    
        if (baseUrl == null || baseUrl.isEmpty()) {
            return TestConnectionResponse.builder()
                .success(false)
                .message("Base URL is required")
                .build();
        }
    
        // Normalize base URL (remove trailing slash)
        String normalizedUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String modelsUrl = normalizedUrl + "/models";
    
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(modelsUrl))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
    
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    
            int statusCode = response.statusCode();
            if (statusCode == 200) {
                return TestConnectionResponse.builder()
                    .success(true)
                    .message("Connection successful")
                    .build();
            } else if (statusCode == 401) {
                return TestConnectionResponse.builder()
                    .success(false)
                    .message("Invalid API key")
                    .build();
            } else {
                return TestConnectionResponse.builder()
                    .success(false)
                    .message("Connection failed: HTTP " + statusCode)
                    .build();
            }
        } catch (java.net.ConnectException e) {
            return TestConnectionResponse.builder()
                .success(false)
                .message("Connection refused: " + baseUrl)
                .build();
        } catch (java.net.SocketTimeoutException e) {
            return TestConnectionResponse.builder()
                .success(false)
                .message("Connection timeout")
                .build();
        } catch (IOException e) {
            log.warn("Provider connection test failed: {}", e.getMessage());
            return TestConnectionResponse.builder()
                .success(false)
                .message("Connection failed: " + e.getMessage())
                .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TestConnectionResponse.builder()
                .success(false)
                .message("Connection interrupted")
                .build();
        } catch (Exception e) {
            log.warn("Provider connection test failed: {}", e.getMessage());
            return TestConnectionResponse.builder()
                .success(false)
                .message("Connection failed: " + e.getClass().getSimpleName())
                .build();
        }
    }

    /**
     * Discover models from a provider.
     * Calls the provider's /models endpoint to fetch available models.
     */
    public DiscoverModelsResponse discoverModels(String providerId, DiscoverModelsRequest request) {
        ProviderInfo provider = getRawProvider(providerId);
        if (provider == null) {
            return DiscoverModelsResponse.builder()
                .success(false)
                .message("Provider '" + providerId + "' not found")
                .build();
        }

        // Get API key: prefer request param, fallback to stored config
        String apiKey = request != null && request.getApiKey() != null
            ? request.getApiKey()
            : provider.getApiKey();

        if (apiKey == null || apiKey.isEmpty()) {
            return DiscoverModelsResponse.builder()
                .success(false)
                .message("API key is required")
                .build();
        }

        // Get base URL: prefer request param, fallback to stored config
        String baseUrl = request != null && request.getBaseUrl() != null
            ? request.getBaseUrl()
            : provider.getBaseUrl();

        if (baseUrl == null || baseUrl.isEmpty()) {
            return DiscoverModelsResponse.builder()
                .success(false)
                .message("Base URL is required")
                .build();
        }

        // Normalize base URL (remove trailing slash)
        String normalizedUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String modelsUrl = normalizedUrl + "/models";

        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(modelsUrl))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            int statusCode = response.statusCode();
            if (statusCode == 200) {
                List<ModelInfo> models = parseModelsResponse(response.body());

                // Sort models by id
                models.sort(Comparator.comparing(ModelInfo::getId));

                log.info("Discovered {} models from provider '{}'", models.size(), providerId);

                return DiscoverModelsResponse.builder()
                    .success(true)
                    .models(models)
                    .message("Discovered " + models.size() + " models")
                    .addedCount(models.size())
                    .build();
            } else if (statusCode == 401) {
                return DiscoverModelsResponse.builder()
                    .success(false)
                    .message("Invalid API key")
                    .build();
            } else {
                String errorMsg = extractErrorMessage(response.body());
                return DiscoverModelsResponse.builder()
                    .success(false)
                    .message("Failed to fetch models: HTTP " + statusCode +
                        (errorMsg != null ? " - " + errorMsg : ""))
                    .build();
            }
        } catch (java.net.ConnectException e) {
            return DiscoverModelsResponse.builder()
                .success(false)
                .message("Connection refused: " + baseUrl)
                .build();
        } catch (java.net.SocketTimeoutException e) {
            return DiscoverModelsResponse.builder()
                .success(false)
                .message("Connection timeout")
                .build();
        } catch (IOException e) {
            log.warn("Model discovery failed for provider '{}': {}", providerId, e.getMessage());
            return DiscoverModelsResponse.builder()
                .success(false)
                .message("Discovery failed: " + e.getMessage())
                .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DiscoverModelsResponse.builder()
                .success(false)
                .message("Discovery interrupted")
                .build();
        } catch (Exception e) {
            log.warn("Model discovery failed for provider '{}': {}", providerId, e.getMessage());
            return DiscoverModelsResponse.builder()
                .success(false)
                .message("Discovery failed: " + e.getClass().getSimpleName())
                .build();
        }
    }

    /**
     * Parse the /models API response and extract model list.
     * Expected format: {"data": [{"id": "model-name", "name": "Model Name"}, ...]}
     */
    private List<ModelInfo> parseModelsResponse(String responseBody) {
        List<ModelInfo> models = new ArrayList<>();
        if (responseBody == null || responseBody.isEmpty()) {
            return models;
        }

        try {
            // Find the "data" array in the JSON response
            int dataIdx = responseBody.indexOf("\"data\"");
            if (dataIdx < 0) {
                return models;
            }

            int arrayStart = responseBody.indexOf("[", dataIdx);
            int arrayEnd = responseBody.lastIndexOf("]");
            if (arrayStart < 0 || arrayEnd < 0 || arrayEnd <= arrayStart) {
                return models;
            }

            String arrayContent = responseBody.substring(arrayStart + 1, arrayEnd);

            // Parse each model object in the array
            Set<String> seenIds = new HashSet<>();
            int idx = 0;
            while (idx < arrayContent.length()) {
                int objStart = arrayContent.indexOf("{", idx);
                if (objStart < 0) break;

                int objEnd = findMatchingBrace(arrayContent, objStart);
                if (objEnd < 0) break;

                String objContent = arrayContent.substring(objStart, objEnd + 1);

                // Extract id and name from the object
                String modelId = extractJsonValue(objContent, "id");
                if (modelId != null && !modelId.isEmpty() && !seenIds.contains(modelId)) {
                    seenIds.add(modelId);
                    String modelName = extractJsonValue(objContent, "name");
                    if (modelName == null || modelName.isEmpty()) {
                        modelName = modelId;
                    }

                    models.add(ModelInfo.builder()
                        .id(modelId)
                        .name(modelName)
                        .probeSource("discovered")
                        .build());
                }

                idx = objEnd + 1;
            }
        } catch (Exception e) {
            log.warn("Failed to parse models response: {}", e.getMessage());
        }

        return models;
    }

    /**
     * Find the matching closing brace for an opening brace at the given position.
     */
    private int findMatchingBrace(String str, int openPos) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = openPos; i < str.length(); i++) {
            char c = str.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                continue;
            }

            if (c == '"') {
                inString = !inString;
                continue;
            }

            if (!inString) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }

        return -1;
    }

    /**
     * Extract a string value from a JSON object string.
     */
    private String extractJsonValue(String jsonObj, String key) {
        // Look for "key": "value" pattern
        String pattern = "\"" + key + "\"";
        int keyIdx = jsonObj.indexOf(pattern);
        if (keyIdx < 0) {
            return null;
        }

        int colonIdx = jsonObj.indexOf(":", keyIdx + pattern.length());
        if (colonIdx < 0) {
            return null;
        }

        // Skip whitespace
        int valueStart = colonIdx + 1;
        while (valueStart < jsonObj.length() && Character.isWhitespace(jsonObj.charAt(valueStart))) {
            valueStart++;
        }

        if (valueStart >= jsonObj.length()) {
            return null;
        }

        // Check if it's a string value
        if (jsonObj.charAt(valueStart) == '"') {
            int valueEnd = jsonObj.indexOf("\"", valueStart + 1);
            // Handle escaped quotes
            while (valueEnd > 0 && jsonObj.charAt(valueEnd - 1) == '\\') {
                valueEnd = jsonObj.indexOf("\"", valueEnd + 1);
            }
            if (valueEnd > valueStart) {
                return jsonObj.substring(valueStart + 1, valueEnd);
            }
        }

        return null;
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
     * Test a specific model by sending a minimal chat completion request.
     */
    public TestConnectionResponse testModel(String providerId, TestModelRequest request) {
        ProviderInfo provider = getRawProvider(providerId);
        if (provider == null) {
            return TestConnectionResponse.builder()
                .success(false)
                .message("Provider '" + providerId + "' not found")
                .build();
        }

        String modelId = request.getModelId();
        if (modelId == null || modelId.isEmpty()) {
            return TestConnectionResponse.builder()
                .success(false)
                .message("Model ID is required")
                .build();
        }

        // Get API key: prefer request param, fallback to stored config
        String apiKey = request.getApiKey() != null 
            ? request.getApiKey() 
            : provider.getApiKey();

        if (apiKey == null || apiKey.isEmpty()) {
            return TestConnectionResponse.builder()
                .success(false)
                .message("API key is required")
                .build();
        }

        // Get base URL: prefer request param, fallback to stored config
        String baseUrl = request.getBaseUrl() != null 
            ? request.getBaseUrl() 
            : provider.getBaseUrl();

        if (baseUrl == null || baseUrl.isEmpty()) {
            return TestConnectionResponse.builder()
                .success(false)
                .message("Base URL is required")
                .build();
        }

        // Normalize base URL (remove trailing slash)
        String normalizedUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String chatCompletionsUrl = normalizedUrl + "/chat/completions";

        // Build minimal chat completion request body
        String requestBody = String.format(
            "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":1}",
            modelId.replace("\\", "\\\\").replace("\"", "\\\"")
        );

        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(chatCompletionsUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(10))
                .build();

            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            int statusCode = response.statusCode();
            if (statusCode == 200) {
                return TestConnectionResponse.builder()
                    .success(true)
                    .message("Model '" + modelId + "' is available")
                    .build();
            } else if (statusCode == 404) {
                return TestConnectionResponse.builder()
                    .success(false)
                    .message("Model '" + modelId + "' not found")
                    .build();
            } else if (statusCode == 401) {
                return TestConnectionResponse.builder()
                    .success(false)
                    .message("Invalid API key")
                    .build();
            } else {
                // Try to extract error message from response body
                String errorMessage = extractErrorMessage(response.body());
                if (errorMessage != null && !errorMessage.isEmpty()) {
                    return TestConnectionResponse.builder()
                        .success(false)
                        .message("Model test failed: " + errorMessage)
                        .build();
                }
                return TestConnectionResponse.builder()
                    .success(false)
                    .message("Model test failed: HTTP " + statusCode)
                    .build();
            }
        } catch (java.net.ConnectException e) {
            return TestConnectionResponse.builder()
                .success(false)
                .message("Connection refused: " + baseUrl)
                .build();
        } catch (java.net.SocketTimeoutException e) {
            return TestConnectionResponse.builder()
                .success(false)
                .message("Connection timeout")
                .build();
        } catch (IOException e) {
            log.warn("Model test failed for {}: {}", modelId, e.getMessage());
            return TestConnectionResponse.builder()
                .success(false)
                .message("Model test failed: " + e.getMessage())
                .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TestConnectionResponse.builder()
                .success(false)
                .message("Connection interrupted")
                .build();
        } catch (Exception e) {
            log.warn("Model test failed for {}: {}", modelId, e.getMessage());
            return TestConnectionResponse.builder()
                .success(false)
                .message("Model test failed: " + e.getClass().getSimpleName())
                .build();
        }
    }

    /**
     * Extract error message from API response body.
     */
    private String extractErrorMessage(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return null;
        }
        // Simple JSON error message extraction
        // Look for "error":{"message":"..."} pattern
        try {
            int errorIdx = responseBody.indexOf("\"error\"");
            if (errorIdx >= 0) {
                int msgIdx = responseBody.indexOf("\"message\"", errorIdx);
                if (msgIdx >= 0) {
                    int start = responseBody.indexOf("\"", msgIdx + 10) + 1;
                    int end = responseBody.indexOf("\"", start);
                    if (start > 0 && end > start) {
                        return responseBody.substring(start, end);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore parsing errors
        }
        return null;
    }

    /**
     * Probe model multimodal capabilities.
     * Sends a chat completion request with an image to test if the model supports multimodal input.
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

        String apiKey = provider.getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            return ProbeMultimodalResponse.builder()
                .supportsImage(false)
                .supportsVideo(false)
                .supportsMultimodal(false)
                .imageMessage("API key is required")
                .videoMessage("Skipped: no API key")
                .build();
        }

        String baseUrl = provider.getBaseUrl();
        if (baseUrl == null || baseUrl.isEmpty()) {
            return ProbeMultimodalResponse.builder()
                .supportsImage(false)
                .supportsVideo(false)
                .supportsMultimodal(false)
                .imageMessage("Base URL is required")
                .videoMessage("Skipped: no base URL")
                .build();
        }

        log.info("Starting multimodal probe for model '{}' on provider '{}'", modelId, providerId);

        // Probe image support
        Object[] imageResult = probeImageSupport(baseUrl, apiKey, modelId);
        boolean supportsImage = (Boolean) imageResult[0];
        String imageMessage = (String) imageResult[1];

        // Skip video probe if image probe failed
        boolean supportsVideo = false;
        String videoMessage = "Skipped: image probe failed";
        if (supportsImage) {
            Object[] videoResult = probeVideoSupport(baseUrl, apiKey, modelId);
            supportsVideo = (Boolean) videoResult[0];
            videoMessage = (String) videoResult[1];
        }

        ProbeMultimodalResponse response = ProbeMultimodalResponse.builder()
            .supportsImage(supportsImage)
            .supportsVideo(supportsVideo)
            .supportsMultimodal(supportsImage || supportsVideo)
            .imageMessage(imageMessage)
            .videoMessage(videoMessage)
            .build();

        // Update model with probe result
        model.setSupportsImage(response.getSupportsImage());
        model.setSupportsVideo(response.getSupportsVideo());
        model.setSupportsMultimodal(response.getSupportsMultimodal());
        model.setProbeSource("probed");

        providerStore.saveProvider(provider);

        log.info("Multimodal probe completed for '{}': image={}, video={}",
            modelId, supportsImage, supportsVideo);

        return response;
    }

    /**
     * Probe image support by sending a solid-red 32x32 PNG.
     * Returns [supported, message].
     */
    private Object[] probeImageSupport(String baseUrl, String apiKey, String modelId) {
        String normalizedUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String chatUrl = normalizedUrl + "/chat/completions";

        // Build request with image
        // Ask the model to identify the dominant color (red) in the probe image
        String requestBody = String.format(
            "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,%s\"}},{\"type\":\"text\",\"text\":\"What is the single dominant color of this image? Reply with ONLY the color name, nothing else.\"}]}],\"max_tokens\":200}",
            escapeJson(modelId), PROBE_IMAGE_B64
        );

        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(chatUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(30))
                .build();

            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            int statusCode = response.statusCode();
            if (statusCode == 200) {
                return evaluateImageResponse(response.body(), modelId);
            } else if (statusCode == 400 || statusCode == 422) {
                // Model doesn't support image input
                String errorMsg = extractErrorMessage(response.body());
                boolean isMediaError = isMediaKeywordError(errorMsg != null ? errorMsg : response.body());
                String message = isMediaError
                    ? "Image not supported: " + (errorMsg != null ? errorMsg : "API rejected image")
                    : "Image not supported: HTTP " + statusCode;
                return new Object[]{false, message};
            } else if (statusCode == 401) {
                return new Object[]{false, "Invalid API key"};
            } else {
                String errorMsg = extractErrorMessage(response.body());
                return new Object[]{false, "Probe inconclusive: HTTP " + statusCode +
                    (errorMsg != null ? " - " + errorMsg : "")};
            }
        } catch (java.net.ConnectException e) {
            return new Object[]{false, "Connection refused: " + baseUrl};
        } catch (java.net.SocketTimeoutException e) {
            return new Object[]{false, "Connection timeout"};
        } catch (IOException e) {
            log.warn("Image probe failed for model '{}': {}", modelId, e.getMessage());
            return new Object[]{false, "Probe failed: " + e.getMessage()};
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Object[]{false, "Probe interrupted"};
        } catch (Exception e) {
            log.warn("Image probe failed for model '{}': {}", modelId, e.getMessage());
            return new Object[]{false, "Probe failed: " + e.getClass().getSimpleName()};
        }
    }

    /**
     * Evaluate image probe response.
     * The probe image is a solid-red 32x32 PNG.
     * Check if the model correctly identifies the red color.
     *
     * IMPORTANT: We only check `content` (the model's final answer), NOT
     * `reasoning_content`.  Text-only reasoning models (e.g. Qwen3-235B-Thinking)
     * silently accept image payloads but cannot perceive them; their thinking
     * chain may coincidentally mention "red" as a guess, causing false positives.
     * The Python reference implementation effectively skips reasoning_content
     * because the OpenAI SDK does not expose it as a standard attribute.
     */
    private Object[] evaluateImageResponse(String responseBody, String modelId) {
        String answer = extractContentFromResponse(responseBody);
        log.info("Image probe for '{}': content='{}'", modelId,
            answer != null ? answer.substring(0, Math.min(answer.length(), 200)) : "null");
        if (answer == null || answer.isEmpty()) {
            return new Object[]{false, "Model returned empty response"};
        }

        answer = answer.toLowerCase().trim();

        // Check for red color keywords in the model's final answer only.
        String[] redKeywords = {"red", "scarlet", "crimson", "vermilion", "maroon", "\u7ea2", "\u7d05"};
        for (String keyword : redKeywords) {
            if (answer.contains(keyword)) {
                log.info("Image probe success for '{}': answer={}", modelId, answer);
                return new Object[]{true, "Image supported (answer=" + answer + ")"};
            }
        }

        log.info("Image probe failed for '{}': model did not recognize image, answer={}", modelId, answer);
        return new Object[]{false, "Model did not recognize image (answer=" + answer + ")"};
    }

    /**
     * Probe video support with automatic format fallback.
     * Tries base64 first, then falls back to HTTP URL (matching Python implementation).
     * Returns [supported, message].
     */
    private Object[] probeVideoSupport(String baseUrl, String apiKey, String modelId) {
        String normalizedUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String chatUrl = normalizedUrl + "/chat/completions";

        // Try base64 first, then HTTP URL (matching Python's fallback strategy)
        String[] videoUrls = {
            "data:video/mp4;base64," + PROBE_VIDEO_B64,
            PROBE_VIDEO_URL,
        };

        String lastErrorMsg = "";
        for (String videoUrl : videoUrls) {
            boolean isHttp = videoUrl.equals(PROBE_VIDEO_URL);
            Object[] result = tryVideoUrl(chatUrl, apiKey, modelId, videoUrl, isHttp);
            if (result != null) {
                return result;
            }
            lastErrorMsg = "format rejected for " + (isHttp ? "HTTP URL" : "base64");
        }

        log.info("Video probe done: model={} result=False (all formats rejected)", modelId);
        return new Object[]{false, "Video not supported: " + lastErrorMsg};
    }

    /**
     * Try a single video URL format. Returns null to indicate the caller should try the next format.
     */
    private Object[] tryVideoUrl(String chatUrl, String apiKey, String modelId, String videoUrl, boolean isHttp) {
        long timeoutSec = isHttp ? 90 : 60;

        String requestBody = String.format(
            "{\"model\":\"%s\",\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"video_url\",\"video_url\":{\"url\":\"%s\"}},{\"type\":\"text\",\"text\":\"What is the single dominant color shown in this video? Reply with ONLY the color name, nothing else.\"}]}],\"max_tokens\":200}",
            escapeJson(modelId), escapeJson(videoUrl)
        );

        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();

            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(chatUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(timeoutSec))
                .build();

            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            int statusCode = response.statusCode();
            if (statusCode == 200) {
                return evaluateVideoResponse(response.body(), modelId, isHttp);
            } else if (statusCode == 400) {
                // 400 means this specific video format was rejected;
                // return null to let the caller try the next URL in the fallback list.
                log.debug("Video probe format rejected (400) for '{}': {}", modelId,
                    isHttp ? "HTTP URL" : "base64");
                return null;
            } else if (statusCode == 422) {
                String errorMsg = extractErrorMessage(response.body());
                boolean isMediaError = isMediaKeywordError(errorMsg != null ? errorMsg : response.body());
                String message = isMediaError
                    ? "Video not supported: " + (errorMsg != null ? errorMsg : "API rejected video")
                    : "Video not supported: HTTP " + statusCode;
                return new Object[]{false, message};
            } else if (statusCode == 401) {
                return new Object[]{false, "Invalid API key"};
            } else {
                String errorMsg = extractErrorMessage(response.body());
                return new Object[]{false, "Probe inconclusive: HTTP " + statusCode +
                    (errorMsg != null ? " - " + errorMsg : "")};
            }
        } catch (java.net.ConnectException e) {
            return new Object[]{false, "Connection refused"};
        } catch (java.net.SocketTimeoutException e) {
            return new Object[]{false, "Connection timeout"};
        } catch (IOException e) {
            log.warn("Video probe failed for model '{}': {}", modelId, e.getMessage());
            return new Object[]{false, "Probe failed: " + e.getMessage()};
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Object[]{false, "Probe interrupted"};
        } catch (Exception e) {
            log.warn("Video probe failed for model '{}': {}", modelId, e.getMessage());
            return new Object[]{false, "Probe failed: " + e.getClass().getSimpleName()};
        }
    }

    /**
     * Evaluate video probe response.
     * The probe video is a solid-blue 64x64 MP4.
     * Check if the model correctly identifies the blue color.
     *
     * For HTTP URL probes, any non-empty response is accepted as evidence of
     * video support (matching Python).  The HTTP URL points to an external video
     * whose content we do not control, so colour-matching is impossible.  This
     * relaxed check is safe because video probe only runs after image probe has
     * passed, filtering out text-only models.
     */
    private Object[] evaluateVideoResponse(String responseBody, String modelId, boolean isHttp) {
        String answer = extractContentFromResponse(responseBody);
        log.info("Video probe for '{}': content='{}' isHttp={}", modelId,
            answer != null ? answer.substring(0, Math.min(answer.length(), 200)) : "null", isHttp);
        if (answer == null || answer.isEmpty()) {
            return new Object[]{false, "Model returned empty response"};
        }

        answer = answer.toLowerCase().trim();

        // Check for blue color keywords in the model's final answer only.
        String[] blueKeywords = {"blue", "navy", "azure", "cobalt", "cyan", "indigo", "\u84dd", "\u85cd"};
        for (String keyword : blueKeywords) {
            if (answer.contains(keyword)) {
                log.info("Video probe success for '{}': answer={}", modelId, answer);
                return new Object[]{true, "Video supported (answer=" + answer + ")"};
            }
        }

        // HTTP URL fallback: accept any non-empty response as evidence
        // of video support (matching Python's relaxed check).
        if (isHttp) {
            log.info("Video probe success for '{}' (http fallback): answer={}", modelId, answer);
            return new Object[]{true, "Video supported (http, answer=" + answer + ")"};
        }

        log.info("Video probe failed for '{}': model did not recognize video, answer={}", modelId, answer);
        return new Object[]{false, "Model did not recognize video (answer=" + answer + ")"};
    }

    /**
     * Extract content from chat completion response using Jackson.
     * Parses choices[0].message.content from the response JSON.
     */
    private String extractContentFromResponse(String responseBody) {
        if (responseBody == null || responseBody.isEmpty()) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) return null;
            JsonNode message = choices.get(0).path("message");
            JsonNode content = message.path("content");
            if (content.isNull() || content.isMissingNode()) return null;
            return content.asText("");
        } catch (Exception e) {
            log.debug("Failed to extract content from response: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Check if an error message contains media-related keywords.
     */
    private boolean isMediaKeywordError(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        String lower = message.toLowerCase();
        String[] keywords = {"image", "video", "vision", "multimodal", "image_url", "video_url", "does not support"};
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Escape a string for JSON.
     */
    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\").replace("\"", "\\\"");
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

    /**
     * Get active model for a specific agent.
     * Returns null if agent has no specific model configured.
     */
    public ModelSlotConfig getAgentActiveModel(String agentId) {
        AgentProfileConfig agentConfig = agentConfigStore.loadAgentConfig(agentId);
        if (agentConfig == null) {
            return null;
        }
        return agentConfig.getActiveModel();
    }

    /**
     * Set active model for a specific agent.
     * If the agent config doesn't exist, it will be created automatically.
     */
    public ModelSlotConfig setAgentActiveModel(String agentId, String providerId, String modelId) {
        ProviderInfo provider = getRawProvider(providerId);
        if (provider == null) {
            throw new IllegalArgumentException("Provider '" + providerId + "' not found");
        }

        if (findModel(provider, modelId) == null) {
            throw new IllegalArgumentException("Model '" + modelId + "' not found in provider '" + providerId + "'");
        }

        AgentProfileConfig agentConfig = agentConfigStore.loadAgentConfig(agentId);
        if (agentConfig == null) {
            // Auto-create agent config if it doesn't exist
            log.info("Agent '{}' config not found, creating new config", agentId);
            agentConfig = agentConfigStore.createAgent(agentId, agentId);
        }

        ModelSlotConfig slot = ModelSlotConfig.builder()
                .providerId(providerId)
                .model(modelId)
                .build();

        agentConfig.setActiveModel(slot);
        agentConfigStore.saveAgentConfig(agentConfig);

        return slot;
    }

    /**
     * Get effective active model for an agent.
     * First checks agent-specific config, falls back to global if not set.
     */
    public ActiveModelsInfo getEffectiveActiveModel(String agentId) {
        // First try agent-specific model
        ModelSlotConfig agentModel = getAgentActiveModel(agentId);
        if (agentModel != null && agentModel.getProviderId() != null && !agentModel.getProviderId().isEmpty()) {
            log.info("Returning agent-specific model for {}: {}/{}", 
                    agentId, agentModel.getProviderId(), agentModel.getModel());
            return ActiveModelsInfo.builder()
                    .activeLlm(agentModel)
                    .build();
        }

        // Fall back to global model
        log.info("No agent-specific model for {}, falling back to global", agentId);
        return getActiveModels();
    }
}
