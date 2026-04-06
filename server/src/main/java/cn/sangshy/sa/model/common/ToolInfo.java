package cn.sangshy.sa.model.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tool information.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolInfo {
    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    @Builder.Default
    private String description = "";

    @JsonProperty("enabled")
    @Builder.Default
    private Boolean enabled = true;

    @JsonProperty("async_execution")
    @Builder.Default
    private Boolean asyncExecution = false;
}
