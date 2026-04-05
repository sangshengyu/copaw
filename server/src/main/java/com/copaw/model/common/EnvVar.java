package com.copaw.model.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Environment variable entry.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnvVar {
    @JsonProperty("key")
    private String key;

    @JsonProperty("value")
    private String value;
}
