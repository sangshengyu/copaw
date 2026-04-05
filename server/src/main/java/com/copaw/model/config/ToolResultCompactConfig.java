package com.copaw.model.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tool result compaction thresholds and retention configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolResultCompactConfig {
    @JsonProperty("enabled")
    @Builder.Default
    private Boolean enabled = true;

    @JsonProperty("recent_n")
    @Builder.Default
    private Integer recentN = 2;

    @JsonProperty("old_max_bytes")
    @Builder.Default
    private Integer oldMaxBytes = 3000;

    @JsonProperty("recent_max_bytes")
    @Builder.Default
    private Integer recentMaxBytes = 50000;

    @JsonProperty("retention_days")
    @Builder.Default
    private Integer retentionDays = 5;
}
