package com.copaw.model.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Complete chat view with spec and state.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatHistory {
    @JsonProperty("messages")
    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    @JsonProperty("status")
    @Builder.Default
    private String status = "idle";
}
