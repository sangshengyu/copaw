package cn.sangshy.sa.model.skill;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to upload skill from workspace to pool.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadToPoolRequest {
    @JsonProperty("workspace_id")
    private String workspaceId;

    @JsonProperty("skill_name")
    private String skillName;

    @JsonProperty("new_name")
    private String newName;

    @JsonProperty("overwrite")
    @Builder.Default
    private Boolean overwrite = false;
}
