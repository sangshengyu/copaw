package cn.sangshy.sa.service;

import cn.sangshy.sa.model.mcp.MCPClientConfig;
import cn.sangshy.sa.model.mcp.MCPClientInfo;
import cn.sangshy.sa.storage.AgentConfigStore;
import cn.sangshy.sa.storage.SADataDir;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Service for managing MCP clients.
 */
@Service
public class McpService {
    private static final Logger log = LoggerFactory.getLogger(McpService.class);

    private final SADataDir dataDir;
    private final AgentConfigStore agentConfigStore;
    private final ObjectMapper objectMapper;

    public McpService(SADataDir dataDir, AgentConfigStore agentConfigStore, ObjectMapper objectMapper) {
        this.dataDir = dataDir;
        this.agentConfigStore = agentConfigStore;
        this.objectMapper = objectMapper;
    }

    /**
     * List all MCP clients for an agent.
     */
    public List<MCPClientInfo> listMcpClients(String agentId) {
        Path agentConfigPath = dataDir.getAgentConfigPath(agentId);
        if (!Files.exists(agentConfigPath)) {
            return new ArrayList<>();
        }

        try {
            JsonNode config = objectMapper.readTree(Files.readString(agentConfigPath));
            JsonNode mcpNode = config.get("mcp");

            if (mcpNode == null || !mcpNode.has("clients")) {
                return new ArrayList<>();
            }

            JsonNode clientsNode = mcpNode.get("clients");
            List<MCPClientInfo> clients = new ArrayList<>();

            clientsNode.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode clientNode = entry.getValue();
                clients.add(buildClientInfo(key, clientNode));
            });

            return clients.stream()
                    .sorted((a, b) -> a.getKey().compareTo(b.getKey()))
                    .collect(Collectors.toList());

        } catch (IOException e) {
            log.warn("Failed to load MCP clients for {}: {}", agentId, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Get a specific MCP client.
     */
    public MCPClientInfo getMcpClient(String agentId, String clientKey) {
        Path agentConfigPath = dataDir.getAgentConfigPath(agentId);
        if (!Files.exists(agentConfigPath)) {
            return null;
        }

        try {
            JsonNode config = objectMapper.readTree(Files.readString(agentConfigPath));
            JsonNode mcpNode = config.get("mcp");

            if (mcpNode == null || !mcpNode.has("clients")) {
                return null;
            }

            JsonNode clientsNode = mcpNode.get("clients");
            if (!clientsNode.has(clientKey)) {
                return null;
            }

            return buildClientInfo(clientKey, clientsNode.get(clientKey));

        } catch (IOException e) {
            log.warn("Failed to get MCP client {} for {}: {}", clientKey, agentId, e.getMessage());
            return null;
        }
    }

    /**
     * Create a new MCP client.
     */
    public MCPClientInfo createMcpClient(String agentId, String clientKey, MCPClientConfig clientConfig) {
        Path agentConfigPath = dataDir.getAgentConfigPath(agentId);

        try {
            ObjectNode config;
            if (Files.exists(agentConfigPath)) {
                config = (ObjectNode) objectMapper.readTree(Files.readString(agentConfigPath));
            } else {
                config = objectMapper.createObjectNode();
                config.put("id", agentId);
            }

            // Ensure mcp node exists
            ObjectNode mcpNode = config.has("mcp") ? (ObjectNode) config.get("mcp")
                    : objectMapper.createObjectNode();

            // Ensure clients node exists
            ObjectNode clientsNode = mcpNode.has("clients") ? (ObjectNode) mcpNode.get("clients")
                    : objectMapper.createObjectNode();

            // Check if client already exists
            if (clientsNode.has(clientKey)) {
                throw new IllegalArgumentException("MCP client already exists: " + clientKey);
            }

            // Add client
            clientsNode.set(clientKey, objectMapper.valueToTree(clientConfig));
            mcpNode.set("clients", clientsNode);
            config.set("mcp", mcpNode);

            // Save config
            Files.createDirectories(agentConfigPath.getParent());
            Files.writeString(agentConfigPath, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(config));

            return buildClientInfo(clientKey, clientsNode.get(clientKey));

        } catch (IOException e) {
            log.error("Failed to create MCP client: {}", e.getMessage());
            throw new RuntimeException("Failed to create MCP client", e);
        }
    }

    /**
     * Update an MCP client.
     */
    public MCPClientInfo updateMcpClient(String agentId, String clientKey, MCPClientConfig updates) {
        Path agentConfigPath = dataDir.getAgentConfigPath(agentId);

        if (!Files.exists(agentConfigPath)) {
            throw new IllegalArgumentException("Agent not found: " + agentId);
        }

        try {
            ObjectNode config = (ObjectNode) objectMapper.readTree(Files.readString(agentConfigPath));

            if (!config.has("mcp") || !config.get("mcp").has("clients")) {
                throw new IllegalArgumentException("MCP client not found: " + clientKey);
            }

            ObjectNode mcpNode = (ObjectNode) config.get("mcp");
            ObjectNode clientsNode = (ObjectNode) mcpNode.get("clients");

            if (!clientsNode.has(clientKey)) {
                throw new IllegalArgumentException("MCP client not found: " + clientKey);
            }

            // Get existing client
            ObjectNode existingClient = (ObjectNode) clientsNode.get(clientKey);

            // Apply updates
            if (updates.getName() != null) {
                existingClient.put("name", updates.getName());
            }
            if (updates.getDescription() != null) {
                existingClient.put("description", updates.getDescription());
            }
            if (updates.getEnabled() != null) {
                existingClient.put("enabled", updates.getEnabled());
            }
            if (updates.getTransport() != null) {
                existingClient.put("transport", updates.getTransport());
            }
            if (updates.getUrl() != null) {
                existingClient.put("url", updates.getUrl());
            }
            if (updates.getHeaders() != null) {
                existingClient.set("headers", objectMapper.valueToTree(updates.getHeaders()));
            }
            if (updates.getCommand() != null) {
                existingClient.put("command", updates.getCommand());
            }
            if (updates.getArgs() != null) {
                existingClient.set("args", objectMapper.valueToTree(updates.getArgs()));
            }
            if (updates.getEnv() != null) {
                existingClient.set("env", objectMapper.valueToTree(updates.getEnv()));
            }
            if (updates.getCwd() != null) {
                existingClient.put("cwd", updates.getCwd());
            }

            // Save config
            Files.writeString(agentConfigPath, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(config));

            return buildClientInfo(clientKey, existingClient);

        } catch (IOException e) {
            log.error("Failed to update MCP client: {}", e.getMessage());
            throw new RuntimeException("Failed to update MCP client", e);
        }
    }

    /**
     * Toggle MCP client enabled status.
     */
    public MCPClientInfo toggleMcpClient(String agentId, String clientKey) {
        Path agentConfigPath = dataDir.getAgentConfigPath(agentId);

        if (!Files.exists(agentConfigPath)) {
            throw new IllegalArgumentException("Agent not found: " + agentId);
        }

        try {
            ObjectNode config = (ObjectNode) objectMapper.readTree(Files.readString(agentConfigPath));

            if (!config.has("mcp") || !config.get("mcp").has("clients")) {
                throw new IllegalArgumentException("MCP client not found: " + clientKey);
            }

            ObjectNode mcpNode = (ObjectNode) config.get("mcp");
            ObjectNode clientsNode = (ObjectNode) mcpNode.get("clients");

            if (!clientsNode.has(clientKey)) {
                throw new IllegalArgumentException("MCP client not found: " + clientKey);
            }

            ObjectNode clientNode = (ObjectNode) clientsNode.get(clientKey);
            boolean currentEnabled = clientNode.has("enabled") && clientNode.get("enabled").asBoolean();
            clientNode.put("enabled", !currentEnabled);

            // Save config
            Files.writeString(agentConfigPath, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(config));

            return buildClientInfo(clientKey, clientNode);

        } catch (IOException e) {
            log.error("Failed to toggle MCP client: {}", e.getMessage());
            throw new RuntimeException("Failed to toggle MCP client", e);
        }
    }

    /**
     * Delete an MCP client.
     */
    public boolean deleteMcpClient(String agentId, String clientKey) {
        Path agentConfigPath = dataDir.getAgentConfigPath(agentId);

        if (!Files.exists(agentConfigPath)) {
            return false;
        }

        try {
            ObjectNode config = (ObjectNode) objectMapper.readTree(Files.readString(agentConfigPath));

            if (!config.has("mcp") || !config.get("mcp").has("clients")) {
                return false;
            }

            ObjectNode mcpNode = (ObjectNode) config.get("mcp");
            ObjectNode clientsNode = (ObjectNode) mcpNode.get("clients");

            if (!clientsNode.has(clientKey)) {
                return false;
            }

            clientsNode.remove(clientKey);

            // Save config
            Files.writeString(agentConfigPath, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(config));

            return true;

        } catch (IOException e) {
            log.error("Failed to delete MCP client: {}", e.getMessage());
            return false;
        }
    }

    // ==================== Helper Methods ====================

    private MCPClientInfo buildClientInfo(String key, JsonNode clientNode) {
        Map<String, String> maskedEnv = new HashMap<>();
        Map<String, String> maskedHeaders = new HashMap<>();

        if (clientNode.has("env") && clientNode.get("env").isObject()) {
            clientNode.get("env").fields().forEachRemaining(entry -> {
                maskedEnv.put(entry.getKey(), maskSensitiveValue(entry.getValue().asText()));
            });
        }

        if (clientNode.has("headers") && clientNode.get("headers").isObject()) {
            clientNode.get("headers").fields().forEachRemaining(entry -> {
                maskedHeaders.put(entry.getKey(), maskSensitiveValue(entry.getValue().asText()));
            });
        }

        List<String> args = new ArrayList<>();
        if (clientNode.has("args") && clientNode.get("args").isArray()) {
            clientNode.get("args").forEach(arg -> args.add(arg.asText()));
        }

        return MCPClientInfo.builder()
                .key(key)
                .name(clientNode.has("name") ? clientNode.get("name").asText() : key)
                .description(clientNode.has("description") ? clientNode.get("description").asText() : "")
                .enabled(clientNode.has("enabled") && clientNode.get("enabled").asBoolean())
                .transport(clientNode.has("transport") ? clientNode.get("transport").asText() : "stdio")
                .url(clientNode.has("url") ? clientNode.get("url").asText() : "")
                .headers(maskedHeaders)
                .command(clientNode.has("command") ? clientNode.get("command").asText() : "")
                .args(args)
                .env(maskedEnv)
                .cwd(clientNode.has("cwd") ? clientNode.get("cwd").asText() : "")
                .build();
    }

    /**
     * Mask sensitive value for display.
     * Short values (<=8 chars) are fully masked.
     * Longer values show first 2-3 chars and last 4 chars.
     */
    private String maskSensitiveValue(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }

        int length = value.length();
        if (length <= 8) {
            return "*".repeat(length);
        }

        // Show first 2-3 characters (3 if there's a dash at position 2)
        int prefixLen = (length > 2 && value.charAt(2) == '-') ? 3 : 2;
        String prefix = value.substring(0, prefixLen);

        // Show last 4 characters
        String suffix = value.substring(length - 4);

        // Calculate masked section length (at least 4 asterisks)
        int maskedLen = Math.max(length - prefixLen - 4, 4);

        return prefix + "*".repeat(maskedLen) + suffix;
    }
}
