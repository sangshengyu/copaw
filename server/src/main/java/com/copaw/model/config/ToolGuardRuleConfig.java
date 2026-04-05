package com.copaw.model.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool Guard rule configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolGuardRuleConfig {
    @JsonProperty("id")
    private String id;

    @JsonProperty("tools")
    @Builder.Default
    private List<String> tools = new ArrayList<>();

    @JsonProperty("params")
    @Builder.Default
    private Map<String, Object> params = new HashMap<>();

    @JsonProperty("category")
    private String category;

    @JsonProperty("severity")
    private String severity;

    @JsonProperty("patterns")
    @Builder.Default
    private List<String> patterns = new ArrayList<>();

    @JsonProperty("exclude_patterns")
    @Builder.Default
    private List<String> excludePatterns = new ArrayList<>();

    @JsonProperty("description")
    private String description;

    @JsonProperty("remediation")
    private String remediation;
}
