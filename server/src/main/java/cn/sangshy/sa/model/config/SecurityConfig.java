package cn.sangshy.sa.model.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Security configuration section.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecurityConfig {
    @JsonProperty("tool_guard")
    @Builder.Default
    private ToolGuardConfig toolGuard = new ToolGuardConfig();

    @JsonProperty("file_guard")
    @Builder.Default
    private FileGuardConfig fileGuard = new FileGuardConfig();

    @JsonProperty("skill_scanner")
    @Builder.Default
    private SkillScannerConfig skillScanner = new SkillScannerConfig();
}
