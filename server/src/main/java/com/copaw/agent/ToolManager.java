package com.copaw.agent;

import com.copaw.agent.tools.EditFileTool;
import com.copaw.agent.tools.ExecuteShellTool;
import com.copaw.agent.tools.GetCurrentTimeTool;
import com.copaw.agent.tools.GlobSearchTool;
import com.copaw.agent.tools.GrepSearchTool;
import com.copaw.agent.tools.ListDirTool;
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

        if (isToolEnabled("edit_file", enabledToolNames)) {
            toolkit.registerTool(new EditFileTool(workspaceDir));
            log.debug("Registered tool: edit_file");
        }

        if (isToolEnabled("list_dir", enabledToolNames)) {
            toolkit.registerTool(new ListDirTool(workspaceDir));
            log.debug("Registered tool: list_dir");
        }

        // Search operations
        if (isToolEnabled("grep_search", enabledToolNames)) {
            toolkit.registerTool(new GrepSearchTool(workspaceDir));
            log.debug("Registered tool: grep_search");
        }

        if (isToolEnabled("glob_search", enabledToolNames)) {
            toolkit.registerTool(new GlobSearchTool(workspaceDir));
            log.debug("Registered tool: glob_search");
        }

        // Shell execution
        if (isToolEnabled("execute_shell", enabledToolNames)) {
            toolkit.registerTool(new ExecuteShellTool(workspaceDir));
            log.debug("Registered tool: execute_shell");
        }

        // Utility tools
        if (isToolEnabled("get_current_time", enabledToolNames)) {
            toolkit.registerTool(new GetCurrentTimeTool());
            log.debug("Registered tool: get_current_time");
        }
        
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
