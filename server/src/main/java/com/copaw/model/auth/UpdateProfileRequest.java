package com.copaw.model.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Update profile request model.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateProfileRequest {
    @JsonProperty("current_password")
    private String currentPassword;

    @JsonProperty("new_username")
    private String newUsername;

    @JsonProperty("new_password")
    private String newPassword;
}
