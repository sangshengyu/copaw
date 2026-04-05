package com.copaw.model.skill;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to install skill from hub.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HubInstallRequest {
    @JsonProperty("bundle_url")
    private String bundleUrl;

    @JsonProperty("version")
    @Builder.Default
    private String version = "";

    @JsonProperty("enable")
    @Builder.Default
    private Boolean enable = true;

    @JsonProperty("target_name")
    @Builder.Default
    private String targetName = "";

    @JsonProperty("overwrite")
    @Builder.Default
    private Boolean overwrite = false;
}
