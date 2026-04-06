package cn.sangshy.sa.model.provider;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Model slot configuration for LLM routing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelSlotConfig {
    @JsonProperty("provider_id")
    @Builder.Default
    private String providerId = "";

    @JsonProperty("model")
    @Builder.Default
    private String model = "";
}
