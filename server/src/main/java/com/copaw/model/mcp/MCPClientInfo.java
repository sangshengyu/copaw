package com.copaw.model.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MCP client information for API responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MCPClientInfo {
    @JsonProperty("key")
    private String key;

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
    private Map<String, String> headers;

    @JsonProperty("command")
    @Builder.Default
    private String command = "";

    @JsonProperty("args")
    private List<String> args;

    @JsonProperty("env")
    private Map<String, String> env;

    @JsonProperty("cwd")
    @Builder.Default
    private String cwd = "";
}
