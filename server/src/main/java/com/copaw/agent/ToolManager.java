package com.copaw.agent;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolkitConfig;
import io.agentscope.core.tool.file.ReadFileTool;
import io.agentscope.core.tool.file.WriteFileTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Manager for built-in tools.
 * Registers all built-in tools and manages enable/disable state.
 */
@Component
public class ToolManager {
    
    private static final Logger log = LoggerFactory.getLogger(ToolManager.class);
    
    private final Map<String, Boolean> enabledTools = new HashMap<>();
    
    /**
     * Create a toolkit with enabled tools for the given workspace.
     *
     * @param workspaceDir The workspace directory
     * @param enabledToolNames Set of enabled tool names (null means all enabled)
     * @return Configured toolkit
     */
    public Toolkit createToolkit(String workspaceDir, Set<String> enabledToolNames) {
        Toolkit toolkit = new Toolkit();
        
        // File operations
        if (isToolEnabled("read_file", enabledToolNames)) {
            toolkit.registerTool(new ReadFileTool(workspaceDir));
            log.debug("Registered tool: read_file");
        }
        
        if (isToolEnabled("write_file", enabledToolNames)) {
            toolkit.registerTool(new WriteFileTool(workspaceDir));
            log.debug("Registered tool: write_file");
        }
        
        // TODO: Register more built-in tools:
        // - edit_file
        // - list_dir  
        // - execute_shell_command
        // - grep_search
        // - glob_search
        // - get_current_time
        // - browser_use (optional)
        // - desktop_screenshot (optional)
        
        log.info("Created toolkit with {} tools for workspace: {}", 
                toolkit.getToolNames().size(), workspaceDir);
        
        return toolkit;
    }
    
    /**
     * Check if a tool is enabled.
     */
    private boolean isToolEnabled(String toolName, Set<String> enabledToolNames) {
        if (enabledToolNames == null) {
            return true; // All enabled by default
        }
        return enabledToolNames.contains(toolName);
    }
    
    /**
     * Set tool enabled state.
     */
    public void setToolEnabled(String toolName, boolean enabled) {
        enabledTools.put(toolName, enabled);
    }
    
    /**
     * Check if tool is enabled globally.
     */
    public boolean isToolEnabled(String toolName) {
        return enabledTools.getOrDefault(toolName, true);
    }
}
