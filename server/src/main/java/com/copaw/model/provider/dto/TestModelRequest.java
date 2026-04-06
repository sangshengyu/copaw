package com.copaw.model.provider.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to test a specific model.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestModelRequest {
    @JsonProperty("model_id")
    private String modelId;

    @JsonProperty("api_key")
    private String apiKey;

    @JsonProperty("base_url")
    private String baseUrl;
}
