package cn.sangshy.sa.model.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Token usage statistics (prompt/completion tokens and call count).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenUsageStats {
    @JsonProperty("prompt_tokens")
    @Builder.Default
    private Long promptTokens = 0L;

    @JsonProperty("completion_tokens")
    @Builder.Default
    private Long completionTokens = 0L;

    @JsonProperty("call_count")
    @Builder.Default
    private Long callCount = 0L;
}
