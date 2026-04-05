package com.copaw.model.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Tool Guard configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolGuardConfig {
    @JsonProperty("enabled")
    @Builder.Default
    private Boolean enabled = true;

    @JsonProperty("rules")
    @Builder.Default
    private List<ToolGuardRuleConfig> rules = new ArrayList<>();
}
