package com.copaw.model.chat;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Chat specification with UUID identifier.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSpec {
    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    @Builder.Default
    private String name = "New Chat";

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("channel")
    @Builder.Default
    private String channel = "console";

    @JsonProperty("created_at")
    private Instant createdAt;

    @JsonProperty("updated_at")
    private Instant updatedAt;

    @JsonProperty("meta")
    @Builder.Default
    private Map<String, Object> meta = new HashMap<>();

    @JsonProperty("status")
    @Builder.Default
    private String status = "idle";
}
