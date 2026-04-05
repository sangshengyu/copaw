package com.copaw.model.provider.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to set active model slot.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelSlotRequest {
    @JsonProperty("provider_id")
    private String providerId;

    @JsonProperty("model")
    private String model;

    @JsonProperty("scope")
    private String scope;

    @JsonProperty("agent_id")
    private String agentId;
}
