package cn.sangshy.sa.model.provider;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Model information for a specific model in a provider.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelInfo {
    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("supports_multimodal")
    private Boolean supportsMultimodal;

    @JsonProperty("supports_image")
    private Boolean supportsImage;

    @JsonProperty("supports_video")
    private Boolean supportsVideo;

    @JsonProperty("probe_source")
    private String probeSource;

    @JsonProperty("generate_kwargs")
    @Builder.Default
    private Map<String, Object> generateKwargs = new HashMap<>();
}
