package com.copaw.model.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Memory summarization and search configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemorySummaryConfig {
    @JsonProperty("memory_summary_enabled")
    @Builder.Default
    private Boolean memorySummaryEnabled = true;

    @JsonProperty("force_memory_search")
    @Builder.Default
    private Boolean forceMemorySearch = false;

    @JsonProperty("force_max_results")
    @Builder.Default
    private Integer forceMaxResults = 1;

    @JsonProperty("force_min_score")
    @Builder.Default
    private Float forceMinScore = 0.3f;

    @JsonProperty("rebuild_memory_index_on_start")
    @Builder.Default
    private Boolean rebuildMemoryIndexOnStart = false;
}
