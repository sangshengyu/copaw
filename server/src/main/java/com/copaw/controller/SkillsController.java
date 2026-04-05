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

    // ==================== Default Agent Skills ====================

    /**
     * List skills for the active/default agent.
     * Frontend calls GET /skills without agent_id to get skills for the current agent.
     */
    @GetMapping("")
    public List<SkillInfo> listSkills() {
        String agentId = agentService.getActiveAgentId();
        return skillService.listWorkspaceSkills(agentId);
    }

    // ==================== Workspace Skills ====================

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
     * Delete a skill from workspace.
     */
    @DeleteMapping("/workspaces/{agent_id}/skills/{skill_name}")
    public Map<String, Boolean> deleteSkill(@PathVariable("agent_id") String agentId,
                                            @PathVariable("skill_name") String skillName) {
        boolean deleted = skillService.deleteWorkspaceSkill(agentId, skillName);
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
     * Install skill from hub.
     */
    @PostMapping("/hub/install")
    public HubInstallTask installFromHub(@RequestParam("agent_id") String agentId,
                                          @RequestBody HubInstallRequest request) {
        return skillService.installFromHub(agentId, request);
    }

    /**
     * Get hub install task status.
     */
    @GetMapping("/hub/install-tasks/{task_id}")
    public HubInstallTask getHubInstallTask(@PathVariable("task_id") String taskId) {
        HubInstallTask task = skillService.getHubInstallTask(taskId);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Install task not found: " + taskId);
        }
        return task;
    }

    /**
     * Cancel hub install task.
     */
    @DeleteMapping("/hub/install-tasks/{task_id}")
    public Map<String, Boolean> cancelHubInstallTask(@PathVariable("task_id") String taskId) {
        boolean cancelled = skillService.cancelHubInstallTask(taskId);
        return Map.of("cancelled", cancelled);
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
