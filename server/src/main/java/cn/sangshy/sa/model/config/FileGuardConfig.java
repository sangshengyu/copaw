package cn.sangshy.sa.model.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * File Guard configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileGuardConfig {
    @JsonProperty("enabled")
    @Builder.Default
    private Boolean enabled = true;

    @JsonProperty("sensitive_files")
    @Builder.Default
    private List<String> sensitiveFiles = new ArrayList<>();
}
