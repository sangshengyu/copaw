package com.copaw.model.config;

import com.copaw.model.provider.ModelSlotConfig;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * LLM routing configuration for agents.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentsLLMRoutingConfig {
    @JsonProperty("enabled")
    @Builder.Default
    private Boolean enabled = false;

    @JsonProperty("mode")
    @Builder.Default
    private String mode = "local_first";

    @JsonProperty("local")
    @Builder.Default
    private ModelSlotConfig local = new ModelSlotConfig();

    @JsonProperty("cloud")
    private ModelSlotConfig cloud;
}
