package com.copaw.model.skill;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Workspace or hub skill details returned to callers.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillInfo {
    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    @Builder.Default
    private String description = "";

    @JsonProperty("version_text")
    @Builder.Default
    private String versionText = "";

    @JsonProperty("content")
    private String content;

    @JsonProperty("source")
    private String source;

    @JsonProperty("references")
    @Builder.Default
    private Map<String, Object> references = new HashMap<>();

    @JsonProperty("scripts")
    @Builder.Default
    private Map<String, Object> scripts = new HashMap<>();
}
