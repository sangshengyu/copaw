package com.copaw.model.provider.dto;

import com.copaw.model.provider.ModelInfo;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Request to create a custom provider.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateCustomProviderRequest {
    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("default_base_url")
    @Builder.Default
    private String defaultBaseUrl = "";

    @JsonProperty("api_key_prefix")
    @Builder.Default
    private String apiKeyPrefix = "";

    @JsonProperty("chat_model")
    @Builder.Default
    private String chatModel = "OpenAIChatModel";

    @JsonProperty("models")
    @Builder.Default
    private List<ModelInfo> models = new ArrayList<>();
}
