package cn.sangshy.sa.model.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * File Guard response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileGuardResponse {
    @JsonProperty("enabled")
    @Builder.Default
    private Boolean enabled = true;

    @JsonProperty("paths")
    @Builder.Default
    private List<String> paths = new ArrayList<>();
}
