package cn.sangshy.sa.model.skill;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Skill specification for storage.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SkillSpec {
    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    @Builder.Default
    private String description = "";

    @JsonProperty("version")
    @Builder.Default
    private String version = "";

    @JsonProperty("version_text")
    @Builder.Default
    private String versionText = "";

    @JsonProperty("commit_text")
    @Builder.Default
    private String commitText = "";

    @JsonProperty("enabled")
    @Builder.Default
    private Boolean enabled = true;

    @JsonProperty("source")
    @Builder.Default
    private String source = "";

    @JsonProperty("signature")
    @Builder.Default
    private String signature = "";

    @JsonProperty("protected")
    @Builder.Default
    private Boolean isProtected = false;

    @JsonProperty("requirements")
    @Builder.Default
    private SkillRequirements requirements = new SkillRequirements();

    @JsonProperty("installed_at")
    private String installedAt;

    @JsonProperty("updated_at")
    private String updatedAt;

    @JsonProperty("config")
    private Map<String, Object> config;

    /**
     * Skill requirements specification.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillRequirements {
        @JsonProperty("require_bins")
        @Builder.Default
        private List<String> requireBins = new ArrayList<>();

        @JsonProperty("require_envs")
        @Builder.Default
        private List<String> requireEnvs = new ArrayList<>();
    }
}
