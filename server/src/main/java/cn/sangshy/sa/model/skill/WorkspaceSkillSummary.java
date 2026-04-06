package cn.sangshy.sa.model.skill;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Summary of skills for a workspace/agent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceSkillSummary {
    @JsonProperty("agent_id")
    private String agentId;

    @JsonProperty("agent_name")
    @Builder.Default
    private String agentName = "";

    @JsonProperty("workspace_dir")
    private String workspaceDir;

    @JsonProperty("skills")
    @Builder.Default
    private List<SkillInfo> skills = new ArrayList<>();
}
