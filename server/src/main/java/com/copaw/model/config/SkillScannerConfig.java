package com.copaw.model.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Skill Scanner configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillScannerConfig {
    @JsonProperty("enabled")
    @Builder.Default
    private Boolean enabled = true;

    @JsonProperty("auto_scan")
    @Builder.Default
    private Boolean autoScan = true;

    @JsonProperty("whitelist")
    @Builder.Default
    private List<SkillScannerWhitelistEntry> whitelist = new ArrayList<>();
}
