package com.copaw.model.skill;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Specification for builtin skill import candidates.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuiltinImportSpec {
    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    @Builder.Default
    private String description = "";

    @JsonProperty("version_text")
    @Builder.Default
    private String versionText = "";

    @JsonProperty("current_version_text")
    @Builder.Default
    private String currentVersionText = "";

    @JsonProperty("current_source")
    @Builder.Default
    private String currentSource = "";

    @JsonProperty("status")
    @Builder.Default
    private String status = "";
}
