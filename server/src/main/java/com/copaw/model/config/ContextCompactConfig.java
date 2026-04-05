package com.copaw.model.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Context compaction and token-counting configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContextCompactConfig {
    @JsonProperty("token_count_model")
    @Builder.Default
    private String tokenCountModel = "default";

    @JsonProperty("token_count_use_mirror")
    @Builder.Default
    private Boolean tokenCountUseMirror = false;

    @JsonProperty("token_count_estimate_divisor")
    @Builder.Default
    private Float tokenCountEstimateDivisor = 4.0f;

    @JsonProperty("context_compact_enabled")
    @Builder.Default
    private Boolean contextCompactEnabled = true;

    @JsonProperty("memory_compact_ratio")
    @Builder.Default
    private Float memoryCompactRatio = 0.75f;

    @JsonProperty("memory_reserve_ratio")
    @Builder.Default
    private Float memoryReserveRatio = 0.1f;

    @JsonProperty("compact_with_thinking_block")
    @Builder.Default
    private Boolean compactWithThinkingBlock = true;
}
