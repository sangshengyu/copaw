package com.copaw.model.agent;

import com.copaw.model.config.*;
import com.copaw.model.mcp.MCPConfig;
import com.copaw.model.provider.ModelSlotConfig;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Complete Agent Profile configuration stored in workspace/agent.json.
 * Each agent has its own configuration file with all settings.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentProfileConfig {
    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    @Builder.Default
    private String description = "";

    @JsonProperty("workspace_dir")
    @Builder.Default
    private String workspaceDir = "";

    @JsonProperty("channels")
    private Object channels;  // ChannelConfig - will be defined later

    @JsonProperty("mcp")
    private MCPConfig mcp;

    @JsonProperty("heartbeat")
    private HeartbeatConfig heartbeat;

    @JsonProperty("last_dispatch")
    private LastDispatchConfig lastDispatch;

    @JsonProperty("running")
    @Builder.Default
    private AgentsRunningConfig running = new AgentsRunningConfig();

    @JsonProperty("llm_routing")
    @Builder.Default
    private AgentsLLMRoutingConfig llmRouting = new AgentsLLMRoutingConfig();

    @JsonProperty("active_model")
    private ModelSlotConfig activeModel;

    @JsonProperty("language")
    @Builder.Default
    private String language = "zh";

    @JsonProperty("system_prompt_files")
    @Builder.Default
    private List<String> systemPromptFiles = new ArrayList<>(List.of("AGENTS.md", "SOUL.md", "PROFILE.md"));

    @JsonProperty("tools")
    private Object tools;  // ToolsConfig - will be defined later

    @JsonProperty("security")
    private Object security;  // SecurityConfig - will be defined later
}
