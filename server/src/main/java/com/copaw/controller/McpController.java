package com.copaw.controller;

import com.copaw.model.mcp.MCPClientConfig;
import com.copaw.model.mcp.MCPClientCreateRequest;
import com.copaw.model.mcp.MCPClientInfo;
import com.copaw.model.mcp.MCPClientUpdateRequest;
import com.copaw.service.AgentService;
import com.copaw.service.McpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Controller for managing MCP clients.
 */
@RestController
@RequestMapping("/mcp")
public class McpController {
    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private final McpService mcpService;
    private final AgentService agentService;

    public McpController(McpService mcpService, AgentService agentService) {
        this.mcpService = mcpService;
        this.agentService = agentService;
    }

    /**
     * Helper to get agent ID from X-Agent-Id header, query param, or active agent.
     */
    private String resolveAgentId(String agentIdHeader, String agentIdParam) {
        if (agentIdHeader != null && !agentIdHeader.isBlank()) {
            return agentIdHeader;
        }
        if (agentIdParam != null && !agentIdParam.isBlank()) {
            return agentIdParam;
        }
        return agentService.getActiveAgentId();
    }

    /**
     * List all MCP clients for an agent.
     */
    @GetMapping("")
    public List<MCPClientInfo> listMcpClients(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader,
            @RequestParam(value = "agent_id", required = false) String agentId) {
        return mcpService.listMcpClients(resolveAgentId(agentIdHeader, agentId));
    }

    /**
     * Get a specific MCP client.
     */
    @GetMapping("/{client_key}")
    public MCPClientInfo getMcpClient(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader,
            @RequestParam(value = "agent_id", required = false) String agentId,
            @PathVariable("client_key") String clientKey) {
        MCPClientInfo client = mcpService.getMcpClient(resolveAgentId(agentIdHeader, agentId), clientKey);
        if (client == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "MCP client not found: " + clientKey);
        }
        return client;
    }

    /**
     * Create a new MCP client.
     */
    @PostMapping("")
    public MCPClientInfo createMcpClient(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader,
            @RequestParam(value = "agent_id", required = false) String agentId,
            @RequestBody Map<String, Object> body) {
        String clientKey = (String) body.get("client_key");
        if (clientKey == null || clientKey.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "client_key is required");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> clientData = (Map<String, Object>) body.get("client");
        if (clientData == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "client is required");
        }

        MCPClientConfig config = buildClientConfig(clientData);

        try {
            return mcpService.createMcpClient(resolveAgentId(agentIdHeader, agentId), clientKey, config);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Update an MCP client.
     */
    @PutMapping("/{client_key}")
    public MCPClientInfo updateMcpClient(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader,
            @RequestParam(value = "agent_id", required = false) String agentId,
            @PathVariable("client_key") String clientKey,
            @RequestBody Map<String, Object> updates) {
        MCPClientConfig config = buildClientConfig(updates);

        try {
            return mcpService.updateMcpClient(resolveAgentId(agentIdHeader, agentId), clientKey, config);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Toggle MCP client enabled status.
     */
    @PatchMapping("/{client_key}/toggle")
    public MCPClientInfo toggleMcpClient(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader,
            @RequestParam(value = "agent_id", required = false) String agentId,
            @PathVariable("client_key") String clientKey) {
        try {
            return mcpService.toggleMcpClient(resolveAgentId(agentIdHeader, agentId), clientKey);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Delete an MCP client.
     */
    @DeleteMapping("/{client_key}")
    public Map<String, String> deleteMcpClient(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader,
            @RequestParam(value = "agent_id", required = false) String agentId,
            @PathVariable("client_key") String clientKey) {
        boolean deleted = mcpService.deleteMcpClient(resolveAgentId(agentIdHeader, agentId), clientKey);
        if (!deleted) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "MCP client not found: " + clientKey);
        }
        return Map.of("message", "MCP client '" + clientKey + "' deleted successfully");
    }

    // ==================== Helper Methods ====================

    @SuppressWarnings("unchecked")
    private MCPClientConfig buildClientConfig(Map<String, Object> data) {
        MCPClientConfig.MCPClientConfigBuilder builder = MCPClientConfig.builder();

        if (data.containsKey("name")) {
            builder.name((String) data.get("name"));
        }
        if (data.containsKey("description")) {
            builder.description((String) data.get("description"));
        }
        if (data.containsKey("enabled")) {
            builder.enabled((Boolean) data.get("enabled"));
        }
        if (data.containsKey("transport")) {
            builder.transport((String) data.get("transport"));
        }
        if (data.containsKey("url")) {
            builder.url((String) data.get("url"));
        }
        if (data.containsKey("headers")) {
            builder.headers((Map<String, String>) data.get("headers"));
        }
        if (data.containsKey("command")) {
            builder.command((String) data.get("command"));
        }
        if (data.containsKey("args")) {
            builder.args((List<String>) data.get("args"));
        }
        if (data.containsKey("env")) {
            builder.env((Map<String, String>) data.get("env"));
        }
        if (data.containsKey("cwd")) {
            builder.cwd((String) data.get("cwd"));
        }

        return builder.build();
    }
}
