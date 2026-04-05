package com.copaw.controller;

import com.copaw.model.common.ToolInfo;
import com.copaw.service.ToolService;
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

    public ToolsController(ToolService toolService) {
        this.toolService = toolService;
    }

    /**
     * List all builtin tools.
     */
    @GetMapping("")
    public List<ToolInfo> listTools() {
        return toolService.listTools();
    }

    /**
     * Toggle tool enabled status.
     */
    @PatchMapping("/{toolName}/toggle")
    public ToolInfo toggleTool(@PathVariable String toolName) {
        try {
            return toolService.toggleTool(toolName);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Update tool async execution setting.
     */
    @PatchMapping("/{toolName}/async-execution")
    public ToolInfo updateAsyncExecution(
            @PathVariable String toolName,
            @RequestBody Map<String, Boolean> body) {
        Boolean asyncExecution = body.get("async_execution");
        if (asyncExecution == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "async_execution is required");
        }
        try {
            return toolService.updateAsyncExecution(toolName, asyncExecution);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
