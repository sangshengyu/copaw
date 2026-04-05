package com.copaw.model.skill;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Pool skill specification for shared skill pool.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PoolSkillSpec {
    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    @Builder.Default
    private String description = "";

    @JsonProperty("version_text")
    @Builder.Default
    private String versionText = "";

    @JsonProperty("content")
    @Builder.Default
    private String content = "";

    @JsonProperty("source")
    @Builder.Default
    private String source = "";

    @JsonProperty("protected")
    @Builder.Default
    private Boolean isProtected = false;

    @JsonProperty("commit_text")
    @Builder.Default
    private String commitText = "";

    @JsonProperty("sync_status")
    @Builder.Default
    private String syncStatus = "";

    @JsonProperty("latest_version_text")
    @Builder.Default
    private String latestVersionText = "";

    @JsonProperty("config")
    private Map<String, Object> config;

    @JsonProperty("last_updated")
    private String lastUpdated;

    @JsonProperty("signature")
    @Builder.Default
    private String signature = "";

    @JsonProperty("added_at")
    private String addedAt;

    @JsonProperty("source_url")
    @Builder.Default
    private String sourceUrl = "";
}
