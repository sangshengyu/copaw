package cn.sangshy.sa.model.provider.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Request to configure a provider.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderConfigRequest {
    @JsonProperty("api_key")
    private String apiKey;

    @JsonProperty("base_url")
    private String baseUrl;

    @JsonProperty("chat_model")
    private String chatModel;

    @JsonProperty("generate_kwargs")
    @Builder.Default
    private Map<String, Object> generateKwargs = new HashMap<>();
}
