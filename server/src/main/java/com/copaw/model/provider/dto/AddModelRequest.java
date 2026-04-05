package com.copaw.model.provider.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to add a model to a provider.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddModelRequest {
    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    private String name;
}
