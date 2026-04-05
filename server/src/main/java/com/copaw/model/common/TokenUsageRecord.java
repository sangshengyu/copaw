package com.copaw.model.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Single row from token usage query (per date + provider + model).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenUsageRecord {
    @JsonProperty("date")
    private String date;

    @JsonProperty("provider_id")
    @Builder.Default
    private String providerId = "";

    @JsonProperty("model")
    private String model;

    @JsonProperty("prompt_tokens")
    @Builder.Default
    private Long promptTokens = 0L;

    @JsonProperty("completion_tokens")
    @Builder.Default
    private Long completionTokens = 0L;

    @JsonProperty("call_count")
    @Builder.Default
    private Long callCount = 0L;
}
