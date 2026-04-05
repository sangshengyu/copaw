package com.copaw.model.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Authentication status response model.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthStatusResponse {
    @JsonProperty("enabled")
    private Boolean enabled;

    @JsonProperty("has_users")
    private Boolean hasUsers;
}
