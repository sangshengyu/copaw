package cn.sangshy.sa.model.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Last channel/user/session that received a user-originated reply.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LastDispatchConfig {
    @JsonProperty("channel")
    @Builder.Default
    private String channel = "";

    @JsonProperty("user_id")
    @Builder.Default
    private String userId = "";

    @JsonProperty("session_id")
    @Builder.Default
    private String sessionId = "";
}
