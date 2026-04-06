package cn.sangshy.sa.model.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Heartbeat configuration for agents.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HeartbeatConfig {
    @JsonProperty("enabled")
    @Builder.Default
    private Boolean enabled = false;

    @JsonProperty("every")
    @Builder.Default
    private String every = "1h";

    @JsonProperty("target")
    @Builder.Default
    private String target = "console";

    @JsonProperty("activeHours")
    private ActiveHoursConfig activeHours;
}
