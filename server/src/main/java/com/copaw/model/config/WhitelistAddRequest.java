package com.copaw.model.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to add a skill to whitelist.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WhitelistAddRequest {
    @JsonProperty("skill_name")
    private String skillName;

    @JsonProperty("content_hash")
    @Builder.Default
    private String contentHash = "";
}
