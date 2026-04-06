package cn.sangshy.sa.model.provider.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response for probing model multimodal capability.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProbeMultimodalResponse {
    @JsonProperty("supports_image")
    @Builder.Default
    private Boolean supportsImage = false;

    @JsonProperty("supports_video")
    @Builder.Default
    private Boolean supportsVideo = false;

    @JsonProperty("supports_multimodal")
    @Builder.Default
    private Boolean supportsMultimodal = false;

    @JsonProperty("image_message")
    @Builder.Default
    private String imageMessage = "";

    @JsonProperty("video_message")
    @Builder.Default
    private String videoMessage = "";
}
