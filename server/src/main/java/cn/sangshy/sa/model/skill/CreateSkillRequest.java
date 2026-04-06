package cn.sangshy.sa.model.skill;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Request to create a new skill.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateSkillRequest {
    @JsonProperty("name")
    private String name;

    @JsonProperty("content")
    private String content;

    @JsonProperty("overwrite")
    @Builder.Default
    private Boolean overwrite = false;

    @JsonProperty("references")
    private Map<String, Object> references;

    @JsonProperty("scripts")
    private Map<String, Object> scripts;

    @JsonProperty("config")
    private Map<String, Object> config;

    @JsonProperty("enable")
    @Builder.Default
    private Boolean enable = true;
}
