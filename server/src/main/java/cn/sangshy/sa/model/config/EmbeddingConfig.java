package cn.sangshy.sa.model.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Embedding model configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmbeddingConfig {
    @JsonProperty("backend")
    @Builder.Default
    private String backend = "openai";

    @JsonProperty("api_key")
    @Builder.Default
    private String apiKey = "";

    @JsonProperty("base_url")
    @Builder.Default
    private String baseUrl = "";

    @JsonProperty("model_name")
    @Builder.Default
    private String modelName = "";

    @JsonProperty("dimensions")
    @Builder.Default
    private Integer dimensions = 1024;

    @JsonProperty("enable_cache")
    @Builder.Default
    private Boolean enableCache = true;

    @JsonProperty("use_dimensions")
    @Builder.Default
    private Boolean useDimensions = false;

    @JsonProperty("max_cache_size")
    @Builder.Default
    private Integer maxCacheSize = 3000;

    @JsonProperty("max_input_length")
    @Builder.Default
    private Integer maxInputLength = 8192;

    @JsonProperty("max_batch_size")
    @Builder.Default
    private Integer maxBatchSize = 10;
}
