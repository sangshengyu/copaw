package com.copaw.controller;

import com.copaw.model.agent.AgentProfileConfig;
import com.copaw.model.agent.AgentProfileRef;
import com.copaw.model.common.MdFileInfo;
import com.copaw.model.config.AgentsRunningConfig;
import com.copaw.service.AgentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Multi-agent management API.
 */
@RestController
@RequestMapping("/agents")
public class AgentsController {

    private final AgentService agentService;

    public AgentsController(AgentService agentService) {
        this.agentService = agentService;
    }

    /**
     * List all agents.
     * GET /agents
     */
    @GetMapping
    public Map<String, Object> listAgents() {
        List<AgentService.AgentSummary> agents = agentService.listAgents();
        List<Map<String, Object>> agentList = agents.stream()
                .map(a -> Map.<String, Object>of(
                        "id", a.getId(),
                        "name", a.getName(),
                        "description", a.getDescription() != null ? a.getDescription() : "",
                        "workspace_dir", a.getWorkspaceDir() != null ? a.getWorkspaceDir() : "",
                        "enabled", a.isEnabled()
                ))
                .toList();
        return Map.of("agents", agentList);
    }

    /**
     * Create a new agent.
     * POST /agents
     */
    @PostMapping
    public ResponseEntity<AgentProfileRef> createAgent(@RequestBody Map<String, Object> request) {
        AgentService.CreateAgentRequest createRequest = new AgentService.CreateAgentRequest();
        createRequest.setName((String) request.get("name"));
        createRequest.setDescription((String) request.get("description"));
        createRequest.setWorkspaceDir((String) request.get("workspace_dir"));
        createRequest.setLanguage((String) request.get("language"));

        if (createRequest.getName() == null || createRequest.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name is required");
        }

        AgentProfileRef ref = agentService.createAgent(createRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(ref);
    }

    /**
     * Get agent details.
     * GET /agents/{agentId}
     */
    @GetMapping("/{agentId}")
    public AgentProfileConfig getAgent(@PathVariable String agentId) {
        try {
            return agentService.getAgentConfig(agentId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Update agent.
     * PUT /agents/{agentId}
     */
    @PutMapping("/{agentId}")
    public AgentProfileConfig updateAgent(
            @PathVariable String agentId,
            @RequestBody AgentProfileConfig config) {
        try {
            return agentService.updateAgent(agentId, config);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Delete agent.
     * DELETE /agents/{agentId}
     */
    @DeleteMapping("/{agentId}")
    public Map<String, Object> deleteAgent(@PathVariable String agentId) {
        try {
            boolean success = agentService.deleteAgent(agentId);
            return Map.of("success", success, "agent_id", agentId);
        } catch (IllegalArgumentException e) {
            if (e.getMessage().contains("Cannot delete")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Persist agent order.
     * PUT /agents/order
     */
    @PutMapping("/order")
    public Map<String, Object> reorderAgents(@RequestBody Map<String, List<String>> request) {
        List<String> agentIds = request.get("agent_ids");
        if (agentIds == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agent_ids is required");
        }
        try {
            return agentService.reorderAgents(agentIds);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Toggle agent enabled state.
     * PATCH /agents/{agentId}/toggle
     */
    @PatchMapping("/{agentId}/toggle")
    public Map<String, Object> toggleAgent(
            @PathVariable String agentId,
            @RequestBody Map<String, Boolean> request) {
        Boolean enabled = request.get("enabled");
        if (enabled == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "enabled is required");
        }
        try {
            return agentService.toggleAgent(agentId, enabled);
        } catch (IllegalArgumentException e) {
            if (e.getMessage().contains("Cannot disable")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * List agent workspace files.
     * GET /agents/{agentId}/files
     */
    @GetMapping("/{agentId}/files")
    public List<MdFileInfo> listAgentFiles(@PathVariable String agentId) {
        try {
            return agentService.listAgentFiles(agentId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Read agent workspace file.
     * GET /agents/{agentId}/files/{filename}
     */
    @GetMapping("/{agentId}/files/{filename}")
    public Map<String, String> readAgentFile(
            @PathVariable String agentId,
            @PathVariable String filename) {
        try {
            String content = agentService.readAgentFile(agentId, filename);
            return Map.of("content", content);
        } catch (IllegalArgumentException e) {
            if (e.getMessage().contains("File not found")) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Write agent workspace file.
     * PUT /agents/{agentId}/files/{filename}
     */
    @PutMapping("/{agentId}/files/{filename}")
    public Map<String, Object> writeAgentFile(
            @PathVariable String agentId,
            @PathVariable String filename,
            @RequestBody Map<String, String> request) {
        String content = request.get("content");
        if (content == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content is required");
        }
        try {
            boolean written = agentService.writeAgentFile(agentId, filename, content);
            return Map.of("written", written, "filename", filename);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * List agent memory files.
     * GET /agents/{agentId}/memory
     */
    @GetMapping("/{agentId}/memory")
    public List<MdFileInfo> listAgentMemory(@PathVariable String agentId) {
        try {
            return agentService.listAgentMemory(agentId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
