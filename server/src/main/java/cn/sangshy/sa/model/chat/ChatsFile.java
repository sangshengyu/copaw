package cn.sangshy.sa.model.chat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Chat registry file for JSON repository.
 * Stores chat_id (UUID) -> session_id mappings for persistence.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatsFile {
    @JsonProperty("version")
    @Builder.Default
    private Integer version = 1;

    @JsonProperty("chats")
    @Builder.Default
    private List<ChatSpec> chats = new ArrayList<>();
}
