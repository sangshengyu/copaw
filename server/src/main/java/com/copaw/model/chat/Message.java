package com.copaw.model.chat;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Chat message with role and content.
 * Supports arbitrary additional fields via @JsonAnySetter.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message {
    @JsonProperty("role")
    private String role;

    @JsonProperty("content")
    private Object content;

    // Additional fields storage
    @Builder.Default
    private Map<String, Object> additionalFields = new HashMap<>();

    @JsonAnySetter
    public void setAdditionalField(String key, Object value) {
        additionalFields.put(key, value);
    }
}
