package com.copaw.controller;

import com.copaw.model.skill.*;
import com.copaw.service.AgentService;
import com.copaw.service.SkillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Controller for managing skills (workspace and pool).
 */
@RestController
@RequestMapping("/skills")
public class SkillsController {
    private static final Logger log = LoggerFactory.getLogger(SkillsController.class);

    private final SkillService skillService;
    private final AgentService agentService;

    public SkillsController(SkillService skillService, AgentService agentService) {
        this.skillService = skillService;
        this.agentService = agentService;
    }

    // ==================== Default Agent Skills (Frontend API) ====================

    /**
     * List skills for the active/default agent.
     * Frontend calls GET /skills without agent_id to get skills for the current agent.
     * Supports X-Agent-Id header for agent isolation.
     */
    @GetMapping("")
    public List<SkillInfo> listSkills(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        String targetAgentId = (agentId != null && !agentId.isBlank())
                ? agentId
                : agentService.getActiveAgentId();
        return skillService.listWorkspaceSkills(targetAgentId);
    }

    /**
     * Refresh skills for the current agent.
     * Supports X-Agent-Id header for agent isolation.
     */
    @PostMapping("/refresh")
    public List<SkillInfo> refreshSkills(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        String targetAgentId = (agentId != null && !agentId.isBlank())
                ? agentId
                : agentService.getActiveAgentId();
        return skillService.listWorkspaceSkills(targetAgentId);
    }

    /**
     * Create a new skill in workspace (frontend endpoint).
     * Supports X-Agent-Id header for agent isolation.
     */
    @PostMapping("")
    public SkillInfo createSkillFrontend(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @RequestBody CreateSkillRequest request) {
        String targetAgentId = (agentId != null && !agentId.isBlank())
                ? agentId
                : agentService.getActiveAgentId();
        try {
            return skillService.createWorkspaceSkill(targetAgentId, request);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /**
     * Save (create or update) a skill (frontend endpoint).
     * Supports X-Agent-Id header for agent isolation.
     */
    @PutMapping("/save")
    public Map<String, Object> saveSkillFrontend(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @RequestBody SaveSkillRequest request) {
        String targetAgentId = (agentId != null && !agentId.isBlank())
                ? agentId
                : agentService.getActiveAgentId();
        try {
            // If source_name is provided and different from name, it's a rename operation
            if (request.getSourceName() != null && !request.getSourceName().equals(request.getName())) {
                SkillInfo updated = skillService.updateWorkspaceSkill(targetAgentId, request.getSourceName(), request);
                return Map.of("success", true, "mode", "rename", "name", updated.getName());
            }
            // Otherwise try to update existing skill
            SkillInfo existing = skillService.getWorkspaceSkill(targetAgentId, request.getName());
            if (existing != null) {
                SkillInfo updated = skillService.updateWorkspaceSkill(targetAgentId, request.getName(), request);
                return Map.of("success", true, "mode", "edit", "name", updated.getName());
            }
            // Create new skill
            CreateSkillRequest createRequest = CreateSkillRequest.builder()
                    .name(request.getName())
                    .content(request.getContent())
                    .config(request.getConfig())
                    .build();
            SkillInfo created = skillService.createWorkspaceSkill(targetAgentId, createRequest);
            return Map.of("success", true, "mode", "create", "name", created.getName());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Enable a skill (frontend endpoint).
     * Supports X-Agent-Id header for agent isolation.
     */
    @PostMapping("/{skill_name}/enable")
    public void enableSkill(
            @PathVariable("skill_name") String skillName,
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        String targetAgentId = (agentId != null && !agentId.isBlank())
                ? agentId
                : agentService.getActiveAgentId();
        try {
            skillService.enableWorkspaceSkill(targetAgentId, skillName);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Disable a skill (frontend endpoint).
     * Supports X-Agent-Id header for agent isolation.
     */
    @PostMapping("/{skill_name}/disable")
    public void disableSkill(
            @PathVariable("skill_name") String skillName,
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        String targetAgentId = (agentId != null && !agentId.isBlank())
                ? agentId
                : agentService.getActiveAgentId();
        try {
            skillService.disableWorkspaceSkill(targetAgentId, skillName);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Batch enable skills (frontend endpoint).
     * Supports X-Agent-Id header for agent isolation.
     */
    @PostMapping("/batch-enable")
    public void batchEnableSkills(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @RequestBody List<String> skillNames) {
        String targetAgentId = (agentId != null && !agentId.isBlank())
                ? agentId
                : agentService.getActiveAgentId();
        for (String skillName : skillNames) {
            try {
                skillService.enableWorkspaceSkill(targetAgentId, skillName);
            } catch (IllegalArgumentException e) {
                log.warn("Failed to enable skill: {}", skillName, e);
            }
        }
    }

    /**
     * Batch delete skills (frontend endpoint).
     * Supports X-Agent-Id header for agent isolation.
     */
    @PostMapping("/batch-delete")
    public Map<String, Object> batchDeleteSkills(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @RequestBody List<String> skillNames) {
        String targetAgentId = (agentId != null && !agentId.isBlank())
                ? agentId
                : agentService.getActiveAgentId();
        Map<String, Map<String, Object>> results = new java.util.HashMap<>();
        for (String skillName : skillNames) {
            boolean deleted = skillService.deleteWorkspaceSkill(targetAgentId, skillName);
            Map<String, Object> result = new java.util.HashMap<>();
            result.put("success", deleted);
            if (!deleted) {
                result.put("reason", "delete_failed");
            }
            results.put(skillName, result);
        }
        return Map.of("results", results);
    }

    /**
     * Get skill config (frontend endpoint).
     * Supports X-Agent-Id header for agent isolation.
     */
    @GetMapping("/{skill_name}/config")
    public Map<String, Object> getSkillConfig(
            @PathVariable("skill_name") String skillName,
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        String targetAgentId = (agentId != null && !agentId.isBlank())
                ? agentId
                : agentService.getActiveAgentId();
        // Get skill info and extract config from the skill's manifest entry
        SkillInfo skill = skillService.getWorkspaceSkill(targetAgentId, skillName);
        if (skill == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill not found: " + skillName);
        }
        // Config is stored in the manifest, we need to read it from there
        // For now, return empty config (will be populated by updateSkillConfig)
        return Map.of("config", Map.of());
    }

    /**
     * Update skill config (frontend endpoint).
     * Supports X-Agent-Id header for agent isolation.
     */
    @PutMapping("/{skill_name}/config")
    public Map<String, Object> updateSkillConfigFrontend(
            @PathVariable("skill_name") String skillName,
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @RequestBody Map<String, Object> request) {
        String targetAgentId = (agentId != null && !agentId.isBlank())
                ? agentId
                : agentService.getActiveAgentId();
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) request.get("config");
        try {
            skillService.updateSkillConfig(targetAgentId, skillName, config);
            return Map.of("updated", true);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Delete skill config (frontend endpoint).
     * Supports X-Agent-Id header for agent isolation.
     */
    @DeleteMapping("/{skill_name}/config")
    public Map<String, Object> deleteSkillConfigFrontend(
            @PathVariable("skill_name") String skillName,
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        String targetAgentId = (agentId != null && !agentId.isBlank())
                ? agentId
                : agentService.getActiveAgentId();
        try {
            skillService.updateSkillConfig(targetAgentId, skillName, Map.of());
            return Map.of("cleared", true);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Update skill channels (frontend endpoint).
     * Supports X-Agent-Id header for agent isolation.
     */
    @PutMapping("/{skill_name}/channels")
    public Map<String, Object> updateSkillChannels(
            @PathVariable("skill_name") String skillName,
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @RequestBody List<String> channels) {
        String targetAgentId = (agentId != null && !agentId.isBlank())
                ? agentId
                : agentService.getActiveAgentId();
        try {
            skillService.updateSkillConfig(targetAgentId, skillName, 
                    Map.of("channels", channels != null ? channels : List.of()));
            return Map.of("updated", true, "channels", channels);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Upload skill from ZIP file (frontend endpoint).
     * Supports X-Agent-Id header for agent isolation.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadSkill(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "enable", defaultValue = "true") boolean enable,
            @RequestParam(value = "overwrite", defaultValue = "false") boolean overwrite,
            @RequestParam(value = "target_name", required = false) String targetName) {
        String targetAgentId = (agentId != null && !agentId.isBlank())
                ? agentId
                : agentService.getActiveAgentId();
        try {
            byte[] data = file.getBytes();
            return skillService.importSkillFromZip(targetAgentId, data, targetName, overwrite, enable);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to read uploaded file");
        }
    }

    // ==================== Workspace Skills (Legacy API with agent_id in path) ====================

    /**
     * List all workspace skill summaries.
     */
    @GetMapping("/workspaces")
    public List<WorkspaceSkillSummary> listWorkspaces() {
        return skillService.listWorkspaces();
    }

    /**
     * List skills for a specific agent/workspace.
     */
    @GetMapping("/workspaces/{agent_id}/skills")
    public List<SkillInfo> listWorkspaceSkills(@PathVariable("agent_id") String agentId) {
        return skillService.listWorkspaceSkills(agentId);
    }

    /**
     * Create a new skill in workspace.
     */
    @PostMapping("/workspaces/{agent_id}/skills")
    public SkillInfo createSkill(@PathVariable("agent_id") String agentId,
                                  @RequestBody CreateSkillRequest request) {
        try {
            return skillService.createWorkspaceSkill(agentId, request);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /**
     * Get a specific skill from workspace.
     */
    @GetMapping("/workspaces/{agent_id}/skills/{skill_name}")
    public SkillInfo getWorkspaceSkill(@PathVariable("agent_id") String agentId,
                                        @PathVariable("skill_name") String skillName) {
        SkillInfo skill = skillService.getWorkspaceSkill(agentId, skillName);
        if (skill == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill not found: " + skillName);
        }
        return skill;
    }

    /**
     * Update a skill in workspace.
     */
    @PutMapping("/workspaces/{agent_id}/skills/{skill_name}")
    public SkillInfo updateSkill(@PathVariable("agent_id") String agentId,
                                  @PathVariable("skill_name") String skillName,
                                  @RequestBody SaveSkillRequest request) {
        try {
            return skillService.updateWorkspaceSkill(agentId, skillName, request);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Delete a skill from workspace (legacy endpoint with agent_id in path).
     */
    @DeleteMapping("/workspaces/{agent_id}/skills/{skill_name}")
    public Map<String, Boolean> deleteSkillLegacy(@PathVariable("agent_id") String agentId,
                                                  @PathVariable("skill_name") String skillName) {
        boolean deleted = skillService.deleteWorkspaceSkill(agentId, skillName);
        if (!deleted) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Failed to delete skill: " + skillName);
        }
        return Map.of("deleted", true);
    }

    /**
     * Delete a skill from workspace (frontend uses this endpoint).
     * Supports X-Agent-Id header for agent isolation.
     */
    @DeleteMapping("/{skill_name}")
    public Map<String, Boolean> deleteSkill(
            @PathVariable("skill_name") String skillName,
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId) {
        String targetAgentId = (agentId != null && !agentId.isBlank())
                ? agentId
                : agentService.getActiveAgentId();
        boolean deleted = skillService.deleteWorkspaceSkill(targetAgentId, skillName);
        if (!deleted) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Failed to delete skill: " + skillName);
        }
        return Map.of("deleted", true);
    }

    /**
     * Toggle skill enabled status.
     */
    @PatchMapping("/workspaces/{agent_id}/skills/{skill_name}/toggle")
    public SkillInfo toggleSkill(@PathVariable("agent_id") String agentId,
                                  @PathVariable("skill_name") String skillName) {
        try {
            return skillService.toggleWorkspaceSkill(agentId, skillName);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Update skill config.
     */
    @PutMapping("/workspaces/{agent_id}/skills/{skill_name}/config")
    public SkillInfo updateSkillConfig(@PathVariable("agent_id") String agentId,
                                        @PathVariable("skill_name") String skillName,
                                        @RequestBody SkillConfigRequest request) {
        try {
            return skillService.updateSkillConfig(agentId, skillName, request.getConfig());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Upload skill from ZIP file.
     */
    @PostMapping(value = "/workspaces/{agent_id}/upload-zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadSkillZip(@PathVariable("agent_id") String agentId,
                                               @RequestParam("file") MultipartFile file,
                                               @RequestParam(value = "enable", defaultValue = "true") boolean enable,
                                               @RequestParam(value = "overwrite", defaultValue = "false") boolean overwrite,
                                               @RequestParam(value = "target_name", required = false) String targetName) {
        try {
            byte[] data = file.getBytes();
            return skillService.importSkillFromZip(agentId, data, targetName, overwrite, enable);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to read uploaded file");
        }
    }

    // ==================== Pool Skills ====================

    /**
     * List all pool skills.
     */
    @GetMapping("/pool")
    public List<PoolSkillSpec> listPoolSkills() {
        return skillService.listPoolSkills();
    }

    /**
     * Refresh pool skills - force reconcile and return updated list.
     */
    @PostMapping("/pool/refresh")
    public List<PoolSkillSpec> refreshPoolSkills() {
        return skillService.reconcilePoolManifest();
    }

    /**
     * Delete a pool skill.
     * Frontend calls DELETE /skills/pool/{skill_name}.
     */
    @DeleteMapping("/pool/{skill_name}")
    public Map<String, Boolean> deletePoolSkill(@PathVariable("skill_name") String skillName) {
        boolean deleted = skillService.deletePoolSkill(skillName);
        if (!deleted) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Skill pool entry cannot be deleted");
        }
        return Map.of("deleted", true);
    }

    /**
     * Batch delete pool skills.
     * Frontend calls POST /skills/pool/batch-delete.
     */
    @PostMapping("/pool/batch-delete")
    public Map<String, Object> batchDeletePoolSkills(@RequestBody List<String> skillNames) {
        Map<String, Map<String, Object>> results = skillService.batchDeletePoolSkills(skillNames);
        return Map.of("results", results);
    }

    /**
     * Create a pool skill.
     * Frontend calls POST /skills/pool/create.
     */
    @PostMapping("/pool/create")
    public Map<String, Object> createPoolSkill(@RequestBody CreateSkillRequest request) {
        Map<String, Object> result = skillService.createPoolSkill(request);
        if (!Boolean.TRUE.equals(result.get("created"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Skill already exists");
        }
        return result;
    }

    /**
     * Save/edit a pool skill.
     * Frontend calls PUT /skills/pool/save.
     */
    @PutMapping("/pool/save")
    public Map<String, Object> savePoolSkill(@RequestBody SaveSkillRequest request) {
        Map<String, Object> result = skillService.savePoolSkill(request);
        if (!Boolean.TRUE.equals(result.get("success"))) {
            String reason = (String) result.get("reason");
            HttpStatus status = "not_found".equals(reason) ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
            throw new ResponseStatusException(status, reason);
        }
        return result;
    }

    /**
     * Get pool builtin sources.
     * Frontend calls GET /skills/pool/builtin-sources.
     */
    @GetMapping("/pool/builtin-sources")
    public List<BuiltinImportSpec> listPoolBuiltinSources() {
        return skillService.listBuiltinCandidates();
    }

    /**
     * Import selected builtin skills into pool.
     * Frontend calls POST /skills/pool/import-builtin.
     */
    @PostMapping("/pool/import-builtin")
    public Map<String, Object> importPoolBuiltins(@RequestBody ImportBuiltinRequest request) {
        return skillService.importBuiltinSkills(request);
    }

    /**
     * Update a single builtin pool skill.
     * Frontend calls POST /skills/pool/{skill_name}/update-builtin.
     */
    @PostMapping("/pool/{skill_name}/update-builtin")
    public Map<String, Object> updatePoolBuiltin(@PathVariable("skill_name") String skillName) {
        try {
            return skillService.updateSingleBuiltin(skillName);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Get pool skill config.
     * Frontend calls GET /skills/pool/{skill_name}/config.
     */
    @GetMapping("/pool/{skill_name}/config")
    public Map<String, Object> getPoolSkillConfig(@PathVariable("skill_name") String skillName) {
        SkillSpec spec = skillService.getPoolSkill(skillName);
        if (spec == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pool skill not found");
        }
        Map<String, Object> config = spec.getConfig() != null ? spec.getConfig() : Map.of();
        return Map.of("config", config);
    }

    /**
     * Update pool skill config.
     * Frontend calls PUT /skills/pool/{skill_name}/config.
     */
    @PutMapping("/pool/{skill_name}/config")
    public Map<String, Object> updatePoolSkillConfig(
            @PathVariable("skill_name") String skillName,
            @RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) request.get("config");
        skillService.updatePoolSkillConfig(skillName, config);
        return Map.of("updated", true);
    }

    /**
     * Delete pool skill config.
     * Frontend calls DELETE /skills/pool/{skill_name}/config.
     */
    @DeleteMapping("/pool/{skill_name}/config")
    public Map<String, Object> deletePoolSkillConfig(@PathVariable("skill_name") String skillName) {
        skillService.updatePoolSkillConfig(skillName, null);
        return Map.of("cleared", true);
    }

    /**
     * Download skill from pool to workspaces.
     */
    @PostMapping("/pool/download")
    public Map<String, Object> downloadFromPool(@RequestBody DownloadFromPoolRequest request) {
        List<Map<String, String>> downloaded = new java.util.ArrayList<>();
        List<Map<String, Object>> conflicts = new java.util.ArrayList<>();

        for (DownloadFromPoolRequest.PoolDownloadTarget target : request.getTargets()) {
            Map<String, Object> result = skillService.downloadFromPool(
                    request.getSkillName(),
                    target.getWorkspaceId(),
                    target.getTargetName(),
                    request.getOverwrite()
            );

            if (Boolean.TRUE.equals(result.get("success"))) {
                downloaded.add(Map.of(
                        "workspace_id", target.getWorkspaceId(),
                        "name", (String) result.get("name")
                ));
            } else {
                conflicts.add(result);
            }
        }

        if (!conflicts.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Some downloads failed");
        }

        return Map.of("downloaded", downloaded);
    }

    /**
     * Upload skill from workspace to pool.
     */
    @PostMapping("/pool/upload")
    public Map<String, Object> uploadToPool(@RequestBody UploadToPoolRequest request) {
        Map<String, Object> result = skillService.uploadToPool(
                request.getWorkspaceId(),
                request.getSkillName(),
                request.getNewName(),
                request.getOverwrite()
        );

        if (!Boolean.TRUE.equals(result.get("success"))) {
            String reason = (String) result.get("reason");
            HttpStatus status = "not_found".equals(reason) ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
            throw new ResponseStatusException(status, (String) result.get("message"));
        }

        return Map.of("success", true);
    }

    /**
     * Import skill from hub URL directly into the pool.
     */
    @PostMapping("/pool/import")
    public Map<String, Object> importPoolSkillFromHub(@RequestBody HubInstallRequest request) {
        try {
            return skillService.importPoolSkillFromHub(request);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ==================== Hub Skills ====================

    /**
     * Search skills in hub.
     */
    @GetMapping("/hub/search")
    public List<HubSkillSpec> searchHub(@RequestParam(value = "q", defaultValue = "") String query,
                                         @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return skillService.searchHubSkills(query, limit);
    }

    /**
     * Install skill from hub (start async task).
     * Frontend calls POST /hub/install/start.
     */
    @PostMapping("/hub/install/start")
    public HubInstallTask installFromHub(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
            @RequestBody HubInstallRequest request) {
        String targetAgentId = (agentId != null && !agentId.isBlank())
                ? agentId
                : agentService.getActiveAgentId();
        return skillService.installFromHub(targetAgentId, request);
    }

    /**
     * Get hub install task status.
     * Frontend calls GET /hub/install/status/{task_id}.
     */
    @GetMapping("/hub/install/status/{task_id}")
    public HubInstallTask getHubInstallStatus(@PathVariable("task_id") String taskId) {
        HubInstallTask task = skillService.getHubInstallTask(taskId);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Install task not found: " + taskId);
        }
        return task;
    }

    /**
     * Cancel hub install task.
     * Frontend calls POST /hub/install/cancel/{task_id}.
     */
    @PostMapping("/hub/install/cancel/{task_id}")
    public Map<String, Object> cancelHubInstall(@PathVariable("task_id") String taskId) {
        HubInstallTask task = skillService.getHubInstallTask(taskId);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Install task not found: " + taskId);
        }
        boolean cancelled = skillService.cancelHubInstallTask(taskId);
        return Map.of(
                "task_id", taskId,
                "status", cancelled ? "cancelled" : task.getStatus()
        );
    }

    // ==================== Builtin Skills ====================

    /**
     * List builtin import candidates.
     */
    @GetMapping("/builtin/candidates")
    public List<BuiltinImportSpec> listBuiltinCandidates() {
        return skillService.listBuiltinCandidates();
    }

    /**
     * Import builtin skills.
     */
    @PostMapping("/import-builtin")
    public List<SkillInfo> importBuiltin(@RequestBody ImportBuiltinRequest request) {
        Map<String, Object> result = skillService.importBuiltinSkills(request);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conflicts = (List<Map<String, Object>>) result.get("conflicts");

        if (conflicts != null && !conflicts.isEmpty() && !Boolean.TRUE.equals(request.getOverwriteConflicts())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Some skills conflict with existing ones");
        }

        // Return empty list for now (would return imported skills)
        return new java.util.ArrayList<>();
    }
}
