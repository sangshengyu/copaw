package com.copaw.model.skill;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Hub install task status.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HubInstallTask {
    @JsonProperty("task_id")
    private String taskId;

    @JsonProperty("bundle_url")
    private String bundleUrl;

    @JsonProperty("version")
    @Builder.Default
    private String version = "";

    @JsonProperty("enable")
    @Builder.Default
    private Boolean enable = true;

    @JsonProperty("overwrite")
    @Builder.Default
    private Boolean overwrite = false;

    @JsonProperty("status")
    @Builder.Default
    private String status = "pending";

    @JsonProperty("error")
    private String error;

    @JsonProperty("result")
    private Map<String, Object> result;

    @JsonProperty("created_at")
    @Builder.Default
    private Double createdAt = (double) System.currentTimeMillis() / 1000;

    @JsonProperty("updated_at")
    @Builder.Default
    private Double updatedAt = (double) System.currentTimeMillis() / 1000;
}
