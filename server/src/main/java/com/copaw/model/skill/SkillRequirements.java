package com.copaw.model.skill;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Skill requirements declared by a skill.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillRequirements {
    @JsonProperty("require_bins")
    @Builder.Default
    private List<String> requireBins = new ArrayList<>();

    @JsonProperty("require_envs")
    @Builder.Default
    private List<String> requireEnvs = new ArrayList<>();
}
