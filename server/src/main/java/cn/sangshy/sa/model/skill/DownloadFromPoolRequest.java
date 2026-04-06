package cn.sangshy.sa.model.skill;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Request to download skill from pool to workspaces.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DownloadFromPoolRequest {
    @JsonProperty("skill_name")
    private String skillName;

    @JsonProperty("targets")
    @Builder.Default
    private List<PoolDownloadTarget> targets = new ArrayList<>();

    @JsonProperty("all_workspaces")
    @Builder.Default
    private Boolean allWorkspaces = false;

    @JsonProperty("overwrite")
    @Builder.Default
    private Boolean overwrite = false;

    /**
     * Target workspace for download.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoolDownloadTarget {
        @JsonProperty("workspace_id")
        private String workspaceId;

        @JsonProperty("target_name")
        private String targetName;
    }
}
