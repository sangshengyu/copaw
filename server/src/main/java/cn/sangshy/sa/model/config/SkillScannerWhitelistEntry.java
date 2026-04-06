package cn.sangshy.sa.model.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Skill Scanner whitelist entry.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillScannerWhitelistEntry {
    @JsonProperty("skill_name")
    private String skillName;

    @JsonProperty("content_hash")
    private String contentHash;

    @JsonProperty("added_at")
    private String addedAt;
}
