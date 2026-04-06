package cn.sangshy.sa.model.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Request to create a new MCP client.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MCPClientCreateRequest {
    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    @Builder.Default
    private String description = "";

    @JsonProperty("enabled")
    @Builder.Default
    private Boolean enabled = true;

    @JsonProperty("transport")
    @Builder.Default
    private String transport = "stdio";

    @JsonProperty("url")
    @Builder.Default
    private String url = "";

    @JsonProperty("headers")
    @Builder.Default
    private Map<String, String> headers = new HashMap<>();

    @JsonProperty("command")
    @Builder.Default
    private String command = "";

    @JsonProperty("args")
    @Builder.Default
    private List<String> args = new ArrayList<>();

    @JsonProperty("env")
    @Builder.Default
    private Map<String, String> env = new HashMap<>();

    @JsonProperty("cwd")
    @Builder.Default
    private String cwd = "";
}
