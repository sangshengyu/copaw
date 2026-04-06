package cn.sangshy.sa.model.skill;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Request to update skill config.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillConfigRequest {
    @JsonProperty("config")
    @Builder.Default
    private Map<String, Object> config = new HashMap<>();
}
