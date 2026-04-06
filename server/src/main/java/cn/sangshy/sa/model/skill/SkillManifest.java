package cn.sangshy.sa.model.skill;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Skill manifest for workspace and pool.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SkillManifest {
    @JsonProperty("schema_version")
    private String schemaVersion;

    @JsonProperty("version")
    @Builder.Default
    private Long version = 0L;

    @JsonProperty("skills")
    @Builder.Default
    private Map<String, SkillSpec> skills = new HashMap<>();

    @JsonProperty("builtin_skill_names")
    private java.util.List<String> builtinSkillNames;
}
