package cn.sangshy.sa.model.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MD file information.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MdFileInfo {
    @JsonProperty("filename")
    private String filename;

    @JsonProperty("path")
    private String path;

    @JsonProperty("size")
    private Long size;

    @JsonProperty("modified_at")
    private String modifiedAt;
}
