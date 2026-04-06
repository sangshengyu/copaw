package cn.sangshy.sa.controller;

import cn.sangshy.sa.model.common.ToolInfo;
import cn.sangshy.sa.service.AgentService;
import cn.sangshy.sa.service.ToolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Controller for managing built-in tools.
 */
@RestController
@RequestMapping("/tools")
public class ToolsController {
    private static final Logger log = LoggerFactory.getLogger(ToolsController.class);

    private final ToolService toolService;
    private final AgentService agentService;

    public ToolsController(ToolService toolService, AgentService agentService) {
        this.toolService = toolService;
        this.agentService = agentService;
    }

    private String resolveAgentId(String agentIdHeader) {
        return (agentIdHeader != null && !agentIdHeader.isBlank())
                ? agentIdHeader
                : agentService.getActiveAgentId();
    }

    /**
     * List all builtin tools.
     */
    @GetMapping("")
    public List<ToolInfo> listTools(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader) {
        String agentId = resolveAgentId(agentIdHeader);
        return toolService.listTools(agentId);
    }

    /**
     * Toggle tool enabled status.
     */
    @PatchMapping("/{toolName}/toggle")
    public ToolInfo toggleTool(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader,
            @PathVariable String toolName) {
        String agentId = resolveAgentId(agentIdHeader);
        try {
            return toolService.toggleTool(agentId, toolName);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Update tool async execution setting.
     */
    @PatchMapping("/{toolName}/async-execution")
    public ToolInfo updateAsyncExecution(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader,
            @PathVariable String toolName,
            @RequestBody Map<String, Boolean> body) {
        String agentId = resolveAgentId(agentIdHeader);
        Boolean asyncExecution = body.get("async_execution");
        if (asyncExecution == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "async_execution is required");
        }
        try {
            return toolService.updateAsyncExecution(agentId, toolName, asyncExecution);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
