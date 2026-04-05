package com.copaw.model.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent profile reference stored in root config.json.
 * Contains only ID and workspace directory reference.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentProfileRef {
    @JsonProperty("id")
    private String id;

    @JsonProperty("workspace_dir")
    private String workspaceDir;

    @JsonProperty("enabled")
    @Builder.Default
    private Boolean enabled = true;
}
