package com.copaw.model.provider;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Active models information.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActiveModelsInfo {
    @JsonProperty("active_llm")
    private ModelSlotConfig activeLlm;
}
