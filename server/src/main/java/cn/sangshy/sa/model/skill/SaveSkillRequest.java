package cn.sangshy.sa.model.skill;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Request to save/update a skill.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaveSkillRequest {
    @JsonProperty("name")
    private String name;

    @JsonProperty("content")
    private String content;

    @JsonProperty("source_name")
    private String sourceName;

    @JsonProperty("config")
    private Map<String, Object> config;
}
