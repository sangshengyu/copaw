package cn.sangshy.sa.model.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Active hours configuration for heartbeat.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActiveHoursConfig {
    @JsonProperty("start")
    @Builder.Default
    private String start = "08:00";

    @JsonProperty("end")
    @Builder.Default
    private String end = "22:00";
}
