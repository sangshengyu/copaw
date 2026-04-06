package cn.sangshy.sa.model.provider.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to discover models from a provider.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiscoverModelsRequest {
    @JsonProperty("api_key")
    private String apiKey;

    @JsonProperty("base_url")
    private String baseUrl;

    @JsonProperty("chat_model")
    private String chatModel;
}
