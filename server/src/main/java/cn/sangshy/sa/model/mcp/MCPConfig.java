package cn.sangshy.sa.model.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP clients configuration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MCPConfig {
    @JsonProperty("clients")
    @Builder.Default
    private Map<String, MCPClientConfig> clients = new HashMap<>();
}
