package com.copaw.model.provider.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response for testing provider/model connection.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestConnectionResponse {
    @JsonProperty("success")
    private Boolean success;

    @JsonProperty("message")
    private String message;
}
