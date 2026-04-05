package com.copaw.model.provider.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Request to configure per-model generation parameters.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelConfigRequest {
    @JsonProperty("generate_kwargs")
    @Builder.Default
    private Map<String, Object> generateKwargs = new HashMap<>();
}
