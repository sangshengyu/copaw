package com.copaw.model.provider.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to test provider connection.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestProviderRequest {
    @JsonProperty("api_key")
    private String apiKey;

    @JsonProperty("base_url")
    private String baseUrl;

    @JsonProperty("chat_model")
    private String chatModel;
}
