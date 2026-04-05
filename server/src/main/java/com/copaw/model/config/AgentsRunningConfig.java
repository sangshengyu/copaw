package com.copaw.model.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent runtime behavior configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentsRunningConfig {
    @JsonProperty("max_iters")
    @Builder.Default
    private Integer maxIters = 100;

    @JsonProperty("llm_retry_enabled")
    @Builder.Default
    private Boolean llmRetryEnabled = true;

    @JsonProperty("llm_max_retries")
    @Builder.Default
    private Integer llmMaxRetries = 3;

    @JsonProperty("llm_backoff_base")
    @Builder.Default
    private Float llmBackoffBase = 1.0f;

    @JsonProperty("llm_backoff_cap")
    @Builder.Default
    private Float llmBackoffCap = 30.0f;

    @JsonProperty("llm_max_concurrent")
    @Builder.Default
    private Integer llmMaxConcurrent = 10;

    @JsonProperty("llm_max_qpm")
    @Builder.Default
    private Integer llmMaxQpm = 0;

    @JsonProperty("llm_rate_limit_pause")
    @Builder.Default
    private Float llmRateLimitPause = 5.0f;

    @JsonProperty("llm_rate_limit_jitter")
    @Builder.Default
    private Float llmRateLimitJitter = 2.0f;

    @JsonProperty("llm_acquire_timeout")
    @Builder.Default
    private Float llmAcquireTimeout = 60.0f;

    @JsonProperty("max_input_length")
    @Builder.Default
    private Integer maxInputLength = 131072;

    @JsonProperty("history_max_length")
    @Builder.Default
    private Integer historyMaxLength = 10000;

    @JsonProperty("context_compact")
    @Builder.Default
    private ContextCompactConfig contextCompact = new ContextCompactConfig();

    @JsonProperty("tool_result_compact")
    @Builder.Default
    private ToolResultCompactConfig toolResultCompact = new ToolResultCompactConfig();

    @JsonProperty("memory_summary")
    @Builder.Default
    private MemorySummaryConfig memorySummary = new MemorySummaryConfig();

    @JsonProperty("embedding_config")
    @Builder.Default
    private EmbeddingConfig embeddingConfig = new EmbeddingConfig();

    @JsonProperty("memory_manager_backend")
    @Builder.Default
    private String memoryManagerBackend = "remelight";
}
