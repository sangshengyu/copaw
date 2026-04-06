package cn.sangshy.sa.model.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * File Guard update request body.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileGuardUpdateBody {
    @JsonProperty("enabled")
    private Boolean enabled;

    @JsonProperty("paths")
    private List<String> paths;
}
