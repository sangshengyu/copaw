package com.copaw.service;

import com.copaw.model.common.ToolInfo;
import com.copaw.storage.ConfigStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for managing built-in tools.
 */
@Service
public class ToolService {
    private static final Logger log = LoggerFactory.getLogger(ToolService.class);

    private final ConfigStore configStore;
    private final ObjectMapper objectMapper;

    // Default builtin tools
    private static final Map<String, ToolInfo> DEFAULT_TOOLS = new HashMap<>();

    static {
        DEFAULT_TOOLS.put("execute_bash", ToolInfo.builder()
            .name("execute_bash")
            .description("Execute bash commands")
            .enabled(true)
            .asyncExecution(false)
            .build());
        
        DEFAULT_TOOLS.put("execute_python", ToolInfo.builder()
            .name("execute_python")
            .description("Execute Python code")
            .enabled(true)
            .asyncExecution(false)
            .build());
        
        DEFAULT_TOOLS.put("read_file", ToolInfo.builder()
            .name("read_file")
            .description("Read file contents")
            .enabled(true)
            .asyncExecution(false)
            .build());
        
        DEFAULT_TOOLS.put("write_file", ToolInfo.builder()
            .name("write_file")
            .description("Write file contents")
            .enabled(true)
            .asyncExecution(false)
            .build());
        
        DEFAULT_TOOLS.put("search_files", ToolInfo.builder()
            .name("search_files")
            .description("Search files in directory")
            .enabled(true)
            .asyncExecution(false)
            .build());
        
        DEFAULT_TOOLS.put("list_dir", ToolInfo.builder()
            .name("list_dir")
            .description("List directory contents")
            .enabled(true)
            .asyncExecution(false)
            .build());
        
        DEFAULT_TOOLS.put("web_search", ToolInfo.builder()
            .name("web_search")
            .description("Search the web")
            .enabled(true)
            .asyncExecution(false)
            .build());
    }

    public ToolService(ConfigStore configStore, ObjectMapper objectMapper) {
        this.configStore = configStore;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        // Initialize tools config if not exists
        initializeToolsIfNeeded();
    }

    private void initializeToolsIfNeeded() {
        JsonNode config = configStore.loadConfig();
        if (!config.has("tools") || !config.get("tools").has("builtin_tools")) {
            ObjectNode toolsNode = objectMapper.createObjectNode();
            ObjectNode builtinToolsNode = objectMapper.createObjectNode();
            
            for (Map.Entry<String, ToolInfo> entry : DEFAULT_TOOLS.entrySet()) {
                builtinToolsNode.set(entry.getKey(), objectMapper.valueToTree(entry.getValue()));
            }
            
            toolsNode.set("builtin_tools", builtinToolsNode);
            ((ObjectNode) config).set("tools", toolsNode);
            configStore.saveConfig(config);
        }
    }

    /**
     * List all builtin tools for the specified agent.
     */
    public List<ToolInfo> listTools(String agentId) {
        JsonNode config = configStore.loadConfig();
        JsonNode toolsNode = config.get("tools");
        
        List<ToolInfo> tools = new ArrayList<>();
        
        if (toolsNode != null && toolsNode.has("builtin_tools")) {
            JsonNode builtinTools = toolsNode.get("builtin_tools");
            builtinTools.fields().forEachRemaining(entry -> {
                try {
                    ToolInfo tool = objectMapper.treeToValue(entry.getValue(), ToolInfo.class);
                    tools.add(tool);
                } catch (Exception e) {
                    log.warn("Failed to parse tool config for {}: {}", entry.getKey(), e.getMessage());
                }
            });
        }
        
        // If no tools found, return defaults
        if (tools.isEmpty()) {
            tools.addAll(DEFAULT_TOOLS.values());
        }
        
        return tools;
    }

    /**
     * Get a specific tool by name for the specified agent.
     */
    public ToolInfo getTool(String agentId, String toolName) {
        JsonNode config = configStore.loadConfig();
        JsonNode toolsNode = config.get("tools");
        
        if (toolsNode != null && toolsNode.has("builtin_tools")) {
            JsonNode toolNode = toolsNode.get("builtin_tools").get(toolName);
            if (toolNode != null) {
                try {
                    return objectMapper.treeToValue(toolNode, ToolInfo.class);
                } catch (Exception e) {
                    log.warn("Failed to parse tool config for {}: {}", toolName, e.getMessage());
                }
            }
        }
        
        return DEFAULT_TOOLS.get(toolName);
    }

    /**
     * Toggle tool enabled status for the specified agent.
     */
    public ToolInfo toggleTool(String agentId, String toolName) {
        ToolInfo tool = getTool(agentId, toolName);
        if (tool == null) {
            throw new IllegalArgumentException("Tool '" + toolName + "' not found");
        }
        
        tool.setEnabled(!tool.getEnabled());
        saveTool(toolName, tool);
        
        return tool;
    }

    /**
     * Update tool async execution setting for the specified agent.
     */
    public ToolInfo updateAsyncExecution(String agentId, String toolName, boolean asyncExecution) {
        ToolInfo tool = getTool(agentId, toolName);
        if (tool == null) {
            throw new IllegalArgumentException("Tool '" + toolName + "' not found");
        }
        
        tool.setAsyncExecution(asyncExecution);
        saveTool(toolName, tool);
        
        return tool;
    }

    private void saveTool(String toolName, ToolInfo tool) {
        JsonNode config = configStore.loadConfig();
        ObjectNode toolsNode;
        ObjectNode builtinToolsNode;
        
        if (config.has("tools") && config.get("tools").isObject()) {
            toolsNode = (ObjectNode) config.get("tools");
        } else {
            toolsNode = objectMapper.createObjectNode();
        }
        
        if (toolsNode.has("builtin_tools") && toolsNode.get("builtin_tools").isObject()) {
            builtinToolsNode = (ObjectNode) toolsNode.get("builtin_tools");
        } else {
            builtinToolsNode = objectMapper.createObjectNode();
        }
        
        builtinToolsNode.set(toolName, objectMapper.valueToTree(tool));
        toolsNode.set("builtin_tools", builtinToolsNode);
        ((ObjectNode) config).set("tools", toolsNode);
        
        configStore.saveConfig(config);
    }
}
