package cn.sangshy.sa.model.chat;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Mutable chat fields accepted from external clients.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatUpdate {
    @JsonProperty("name")
    private String name;
}
