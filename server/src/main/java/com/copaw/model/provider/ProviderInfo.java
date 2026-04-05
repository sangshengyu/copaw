package com.copaw.model.provider;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider information including configuration and models.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderInfo {
    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("base_url")
    @Builder.Default
    private String baseUrl = "";

    @JsonProperty("api_key")
    @Builder.Default
    private String apiKey = "";

    @JsonProperty("chat_model")
    @Builder.Default
    private String chatModel = "OpenAIChatModel";

    @JsonProperty("models")
    @Builder.Default
    private List<ModelInfo> models = new ArrayList<>();

    @JsonProperty("extra_models")
    @Builder.Default
    private List<ModelInfo> extraModels = new ArrayList<>();

    @JsonProperty("api_key_prefix")
    @Builder.Default
    private String apiKeyPrefix = "";

    @JsonProperty("is_local")
    @Builder.Default
    private Boolean isLocal = false;

    @JsonProperty("freeze_url")
    @Builder.Default
    private Boolean freezeUrl = false;

    @JsonProperty("require_api_key")
    @Builder.Default
    private Boolean requireApiKey = true;

    @JsonProperty("is_custom")
    @Builder.Default
    private Boolean isCustom = false;

    @JsonProperty("support_model_discovery")
    @Builder.Default
    private Boolean supportModelDiscovery = false;

    @JsonProperty("support_connection_check")
    @Builder.Default
    private Boolean supportConnectionCheck = true;

    @JsonProperty("generate_kwargs")
    @Builder.Default
    private Map<String, Object> generateKwargs = new HashMap<>();
}
