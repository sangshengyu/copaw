package cn.sangshy.sa.model.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Markdown file content model.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MdFileContent {
    @JsonProperty("content")
    private String content;
}
