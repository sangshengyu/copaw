package com.copaw.controller;

import com.copaw.model.agent.AgentProfileConfig;
import com.copaw.model.common.MdFileInfo;
import com.copaw.model.config.AgentsRunningConfig;
import com.copaw.service.AgentService;
import com.copaw.storage.AgentConfigStore;
import com.copaw.storage.ConfigStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent file management API for the active agent.
 */
@RestController
@RequestMapping("/agent")
public class AgentController {

    private static final Set<String> VALID_LANGUAGES = Set.of("zh", "en", "ru");

    private final AgentService agentService;
    private final AgentConfigStore agentConfigStore;
    private final ConfigStore configStore;

    public AgentController(
            AgentService agentService,
            AgentConfigStore agentConfigStore,
            ConfigStore configStore) {
        this.agentService = agentService;
        this.agentConfigStore = agentConfigStore;
        this.configStore = configStore;
    }

    /**
     * Get the active agent ID.
     */
    private String getActiveAgentId() {
        return agentService.getActiveAgentId();
    }

    /**
     * List working files for active agent.
     * GET /agent/files
     */
    @GetMapping("/files")
    public List<MdFileInfo> listWorkingFiles() {
        String agentId = getActiveAgentId();
        return agentService.listAgentFiles(agentId);
    }

    /**
     * Read a working file for active agent.
     * GET /agent/files/{md_name}
     */
    @GetMapping("/files/{md_name}")
    public Map<String, String> readWorkingFile(@PathVariable("md_name") String mdName) {
        String agentId = getActiveAgentId();
        try {
            String content = agentService.readAgentFile(agentId, mdName);
            return Map.of("content", content);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Write a working file for active agent.
     * PUT /agent/files/{md_name}
     */
    @PutMapping("/files/{md_name}")
    public Map<String, Object> writeWorkingFile(
            @PathVariable("md_name") String mdName,
            @RequestBody Map<String, String> request) {
        String content = request.get("content");
        if (content == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content is required");
        }
        String agentId = getActiveAgentId();
        boolean written = agentService.writeAgentFile(agentId, mdName, content);
        return Map.of("written", written);
    }

    /**
     * List memory files for active agent.
     * GET /agent/memory
     */
    @GetMapping("/memory")
    public List<MdFileInfo> listMemoryFiles() {
        String agentId = getActiveAgentId();
        return agentService.listAgentMemory(agentId);
    }

    /**
     * Read a memory file for active agent.
     * GET /agent/memory/{md_name}
     */
    @GetMapping("/memory/{md_name}")
    public Map<String, String> readMemoryFile(@PathVariable("md_name") String mdName) {
        String agentId = getActiveAgentId();
        try {
            String content = agentService.readAgentFile(agentId, "memory/" + mdName);
            return Map.of("content", content);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Write a memory file for active agent.
     * PUT /agent/memory/{md_name}
     */
    @PutMapping("/memory/{md_name}")
    public Map<String, Object> writeMemoryFile(
            @PathVariable("md_name") String mdName,
            @RequestBody Map<String, String> request) {
        String content = request.get("content");
        if (content == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content is required");
        }
        String agentId = getActiveAgentId();
        boolean written = agentService.writeAgentFile(agentId, "memory/" + mdName, content);
        return Map.of("written", written);
    }

    /**
     * Get agent language.
     * GET /agent/language
     */
    @GetMapping("/language")
    public Map<String, Object> getAgentLanguage() {
        String agentId = getActiveAgentId();
        AgentProfileConfig config = agentService.getAgentConfig(agentId);
        return Map.of(
                "language", config.getLanguage() != null ? config.getLanguage() : "zh",
                "agent_id", agentId
        );
    }

    /**
     * Update agent language.
     * PUT /agent/language
     */
    @PutMapping("/language")
    public Map<String, Object> updateAgentLanguage(@RequestBody Map<String, String> request) {
        String language = request.get("language");
        if (language == null || language.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "language is required");
        }

        language = language.strip().toLowerCase();
        if (!VALID_LANGUAGES.contains(language)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid language '" + language + "'. Must be one of: en, ru, zh");
        }

        String agentId = getActiveAgentId();
        AgentProfileConfig config = agentService.getAgentConfig(agentId);
        String oldLanguage = config.getLanguage();
        config.setLanguage(language);
        agentConfigStore.saveAgentConfig(config);

        // Return copied files (empty for now, would need to implement file copying logic)
        List<String> copiedFiles = List.of();

        return Map.of(
                "language", language,
                "copied_files", copiedFiles,
                "agent_id", agentId
        );
    }

    /**
     * Get agent running config.
     * GET /agent/running-config
     */
    @GetMapping("/running-config")
    public AgentsRunningConfig getRunningConfig() {
        String agentId = getActiveAgentId();
        return agentService.getRunningConfig(agentId);
    }

    /**
     * Update agent running config.
     * PUT /agent/running-config
     */
    @PutMapping("/running-config")
    public AgentsRunningConfig updateRunningConfig(@RequestBody AgentsRunningConfig config) {
        String agentId = getActiveAgentId();
        return agentService.updateRunningConfig(agentId, config);
    }

    /**
     * Get system prompt files.
     * GET /agent/system-prompt-files
     */
    @GetMapping("/system-prompt-files")
    public List<String> getSystemPromptFiles() {
        String agentId = getActiveAgentId();
        return agentService.getSystemPromptFiles(agentId);
    }

    /**
     * Update system prompt files.
     * PUT /agent/system-prompt-files
     */
    @PutMapping("/system-prompt-files")
    public List<String> updateSystemPromptFiles(@RequestBody List<String> files) {
        String agentId = getActiveAgentId();
        return agentService.updateSystemPromptFiles(agentId, files);
    }
}
