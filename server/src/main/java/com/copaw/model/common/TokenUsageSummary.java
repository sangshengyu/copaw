package com.copaw.model.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Aggregated token usage summary.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenUsageSummary {
    @JsonProperty("total_prompt_tokens")
    @Builder.Default
    private Long totalPromptTokens = 0L;

    @JsonProperty("total_completion_tokens")
    @Builder.Default
    private Long totalCompletionTokens = 0L;

    @JsonProperty("total_calls")
    @Builder.Default
    private Long totalCalls = 0L;

    @JsonProperty("by_model")
    @Builder.Default
    private Map<String, TokenUsageByModel> byModel = new HashMap<>();

    @JsonProperty("by_provider")
    @Builder.Default
    private Map<String, TokenUsageStats> byProvider = new HashMap<>();

    @JsonProperty("by_date")
    @Builder.Default
    private Map<String, TokenUsageStats> byDate = new HashMap<>();
}
