package com.copaw.model.agent;

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
 * Agents configuration stored in root config.json.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentsConfig {
    @JsonProperty("active_agent")
    @Builder.Default
    private String activeAgent = "default";

    @JsonProperty("agent_order")
    @Builder.Default
    private List<String> agentOrder = new ArrayList<>(List.of("default"));

    @JsonProperty("profiles")
    @Builder.Default
    private Map<String, AgentProfileRef> profiles = new HashMap<>();

    // Legacy fields for backward compatibility
    @JsonProperty("defaults")
    private Object defaults;

    @JsonProperty("running")
    private Object running;

    @JsonProperty("llm_routing")
    private Object llmRouting;

    @JsonProperty("language")
    @Builder.Default
    private String language = "zh";

    @JsonProperty("installed_md_files_language")
    private String installedMdFilesLanguage;

    @JsonProperty("system_prompt_files")
    @Builder.Default
    private List<String> systemPromptFiles = new ArrayList<>(List.of("AGENTS.md", "SOUL.md", "PROFILE.md"));
}
