package com.copaw.service;

import com.copaw.model.skill.*;
import com.copaw.storage.AgentConfigStore;
import com.copaw.storage.CoPawDataDir;
import com.copaw.storage.SkillPoolStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for managing skills (workspace and pool).
 */
@Service
public class SkillService {
    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    private static final String SKILL_MANIFEST_FILE = "skill.json";
    private static final String SKILL_MD_FILE = "SKILL.md";

    private final CoPawDataDir dataDir;
    private final SkillPoolStore skillPoolStore;
    private final AgentConfigStore agentConfigStore;
    private final ObjectMapper objectMapper;

    public SkillService(CoPawDataDir dataDir, SkillPoolStore skillPoolStore,
                        AgentConfigStore agentConfigStore, ObjectMapper objectMapper) {
        this.dataDir = dataDir;
        this.skillPoolStore = skillPoolStore;
        this.agentConfigStore = agentConfigStore;
        this.objectMapper = objectMapper;
    }

    // ==================== Workspace Skills ====================

    /**
     * List all workspace skill summaries.
     */
    public List<WorkspaceSkillSummary> listWorkspaces() {
        List<WorkspaceSkillSummary> summaries = new ArrayList<>();

        // Get all agent directories
        Path agentsDir = dataDir.getAgentsDir();
        if (!Files.exists(agentsDir)) {
            return summaries;
        }

        try {
            Files.list(agentsDir).forEach(agentDir -> {
                if (Files.isDirectory(agentDir)) {
                    String agentId = agentDir.getFileName().toString();
                    summaries.add(getWorkspaceSummary(agentId));
                }
            });
        } catch (IOException e) {
            log.warn("Failed to list workspaces: {}", e.getMessage());
        }

        return summaries;
    }

    /**
     * Get workspace skill summary for an agent.
     */
    public WorkspaceSkillSummary getWorkspaceSummary(String agentId) {
        Path workspaceDir = dataDir.getAgentDir(agentId);

        // Try to get agent name from config
        String agentName = agentId;
        var agentConfig = agentConfigStore.loadAgentConfig(agentId);
        if (agentConfig != null && agentConfig.getName() != null) {
            agentName = agentConfig.getName();
        }

        return WorkspaceSkillSummary.builder()
                .agentId(agentId)
                .agentName(agentName)
                .workspaceDir(workspaceDir.toString())
                .skills(listWorkspaceSkills(agentId))
                .build();
    }

    /**
     * List skills for a workspace.
     */
    public List<SkillInfo> listWorkspaceSkills(String agentId) {
        Path workspaceDir = dataDir.getAgentDir(agentId);
        return listWorkspaceSkills(workspaceDir);
    }

    /**
     * List skills for a workspace directory.
     */
    public List<SkillInfo> listWorkspaceSkills(Path workspaceDir) {
        List<SkillInfo> skills = new ArrayList<>();
        Path manifestPath = getWorkspaceSkillManifestPath(workspaceDir);

        if (!Files.exists(manifestPath)) {
            return skills;
        }

        try {
            JsonNode manifest = objectMapper.readTree(Files.readString(manifestPath));
            JsonNode skillsNode = manifest.get("skills");

            if (skillsNode != null && skillsNode.isObject()) {
                skillsNode.fields().forEachRemaining(entry -> {
                    String skillName = entry.getKey();
                    JsonNode skillNode = entry.getValue();

                    SkillInfo skill = readSkillFromWorkspace(workspaceDir, skillName, skillNode);
                    if (skill != null) {
                        skills.add(skill);
                    }
                });
            }
        } catch (IOException e) {
            log.warn("Failed to load workspace skills: {}", e.getMessage());
        }

        return skills.stream()
                .sorted(Comparator.comparing(SkillInfo::getName))
                .collect(Collectors.toList());
    }

    /**
     * Get a specific skill from workspace.
     */
    public SkillInfo getWorkspaceSkill(String agentId, String skillName) {
        Path workspaceDir = dataDir.getAgentDir(agentId);
        Path manifestPath = getWorkspaceSkillManifestPath(workspaceDir);

        if (!Files.exists(manifestPath)) {
            return null;
        }

        try {
            JsonNode manifest = objectMapper.readTree(Files.readString(manifestPath));
            JsonNode skillsNode = manifest.get("skills");

            if (skillsNode != null && skillsNode.has(skillName)) {
                return readSkillFromWorkspace(workspaceDir, skillName, skillsNode.get(skillName));
            }
        } catch (IOException e) {
            log.warn("Failed to get workspace skill: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Create a new skill in workspace.
     */
    public SkillInfo createWorkspaceSkill(String agentId, CreateSkillRequest request) {
        Path workspaceDir = dataDir.getAgentDir(agentId);
        Path skillDir = workspaceDir.resolve("skills").resolve(request.getName());

        // Check if skill already exists
        if (Files.exists(skillDir) && !Boolean.TRUE.equals(request.getOverwrite())) {
            throw new IllegalArgumentException("Skill already exists: " + request.getName());
        }

        try {
            // Create skill directory
            Files.createDirectories(skillDir);

            // Write SKILL.md
            Path skillMdPath = skillDir.resolve(SKILL_MD_FILE);
            Files.writeString(skillMdPath, request.getContent() != null ? request.getContent() : "");

            // Update manifest
            updateWorkspaceManifest(workspaceDir, request.getName(), request.getEnable(),
                    request.getConfig(), "customized");

            // Reload and return
            reconcileWorkspaceManifest(workspaceDir);
            return getWorkspaceSkill(agentId, request.getName());

        } catch (IOException e) {
            log.error("Failed to create skill: {}", e.getMessage());
            throw new RuntimeException("Failed to create skill", e);
        }
    }

    /**
     * Update a skill in workspace.
     */
    public SkillInfo updateWorkspaceSkill(String agentId, String skillName, SaveSkillRequest request) {
        Path workspaceDir = dataDir.getAgentDir(agentId);
        Path skillDir = workspaceDir.resolve("skills").resolve(skillName);

        if (!Files.exists(skillDir)) {
            throw new IllegalArgumentException("Skill not found: " + skillName);
        }

        try {
            // Update SKILL.md if content provided
            if (request.getContent() != null) {
                Path skillMdPath = skillDir.resolve(SKILL_MD_FILE);
                Files.writeString(skillMdPath, request.getContent());
            }

            // Update config if provided
            if (request.getConfig() != null) {
                updateSkillConfig(workspaceDir, skillName, request.getConfig());
            }

            reconcileWorkspaceManifest(workspaceDir);
            return getWorkspaceSkill(agentId, skillName);

        } catch (IOException e) {
            log.error("Failed to update skill: {}", e.getMessage());
            throw new RuntimeException("Failed to update skill", e);
        }
    }

    /**
     * Delete a skill from workspace.
     */
    public boolean deleteWorkspaceSkill(String agentId, String skillName) {
        Path workspaceDir = dataDir.getAgentDir(agentId);
        Path skillDir = workspaceDir.resolve("skills").resolve(skillName);

        // First disable the skill
        disableWorkspaceSkill(agentId, skillName);

        if (!Files.exists(skillDir)) {
            return false;
        }

        try {
            // Delete skill directory
            Files.walk(skillDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete {}: {}", path, e.getMessage());
                        }
                    });

            // Update manifest
            removeSkillFromManifest(workspaceDir, skillName);

            return true;
        } catch (IOException e) {
            log.error("Failed to delete skill: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Toggle skill enabled status.
     */
    public SkillInfo toggleWorkspaceSkill(String agentId, String skillName) {
        Path workspaceDir = dataDir.getAgentDir(agentId);
        Path manifestPath = getWorkspaceSkillManifestPath(workspaceDir);

        if (!Files.exists(manifestPath)) {
            throw new IllegalArgumentException("Skill not found: " + skillName);
        }

        try {
            JsonNode manifest = objectMapper.readTree(Files.readString(manifestPath));
            ObjectNode skillsNode = (ObjectNode) manifest.get("skills");

            if (skillsNode == null || !skillsNode.has(skillName)) {
                throw new IllegalArgumentException("Skill not found: " + skillName);
            }

            ObjectNode skillNode = (ObjectNode) skillsNode.get(skillName);
            boolean currentEnabled = skillNode.has("enabled") && skillNode.get("enabled").asBoolean();
            skillNode.put("enabled", !currentEnabled);

            Files.writeString(manifestPath, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(manifest));

            return getWorkspaceSkill(agentId, skillName);
        } catch (IOException e) {
            log.error("Failed to toggle skill: {}", e.getMessage());
            throw new RuntimeException("Failed to toggle skill", e);
        }
    }

    /**
     * Enable a skill.
     */
    public boolean enableWorkspaceSkill(String agentId, String skillName) {
        return setSkillEnabled(agentId, skillName, true);
    }

    /**
     * Disable a skill.
     */
    public boolean disableWorkspaceSkill(String agentId, String skillName) {
        return setSkillEnabled(agentId, skillName, false);
    }

    private boolean setSkillEnabled(String agentId, String skillName, boolean enabled) {
        Path workspaceDir = dataDir.getAgentDir(agentId);
        Path manifestPath = getWorkspaceSkillManifestPath(workspaceDir);

        if (!Files.exists(manifestPath)) {
            return false;
        }

        try {
            JsonNode manifest = objectMapper.readTree(Files.readString(manifestPath));
            ObjectNode skillsNode = (ObjectNode) manifest.get("skills");

            if (skillsNode == null || !skillsNode.has(skillName)) {
                return false;
            }

            ObjectNode skillNode = (ObjectNode) skillsNode.get(skillName);
            skillNode.put("enabled", enabled);

            Files.writeString(manifestPath, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(manifest));

            return true;
        } catch (IOException e) {
            log.error("Failed to set skill enabled: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Update skill config.
     */
    public SkillInfo updateSkillConfig(String agentId, String skillName, Map<String, Object> config) {
        Path workspaceDir = dataDir.getAgentDir(agentId);
        updateSkillConfig(workspaceDir, skillName, config);
        return getWorkspaceSkill(agentId, skillName);
    }

    private void updateSkillConfig(Path workspaceDir, String skillName, Map<String, Object> config) {
        Path manifestPath = getWorkspaceSkillManifestPath(workspaceDir);

        try {
            JsonNode manifest = objectMapper.readTree(Files.readString(manifestPath));
            ObjectNode skillsNode = (ObjectNode) manifest.get("skills");

            if (skillsNode == null || !skillsNode.has(skillName)) {
                throw new IllegalArgumentException("Skill not found: " + skillName);
            }

            ObjectNode skillNode = (ObjectNode) skillsNode.get(skillName);
            skillNode.set("config", objectMapper.valueToTree(config));

            Files.writeString(manifestPath, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(manifest));
        } catch (IOException e) {
            log.error("Failed to update skill config: {}", e.getMessage());
            throw new RuntimeException("Failed to update skill config", e);
        }
    }

    /**
     * Import skill from ZIP upload.
     */
    public Map<String, Object> importSkillFromZip(String agentId, byte[] zipData, String targetName,
                                                   boolean overwrite, boolean enable) {
        // TODO: Implement ZIP import functionality
        log.info("Import skill from ZIP for agent: {}, target: {}", agentId, targetName);
        return Map.of("success", true, "skill_name", targetName != null ? targetName : "imported_skill");
    }

    // ==================== Pool Skills ====================

    /**
     * List all pool skills.
     */
    public List<PoolSkillSpec> listPoolSkills() {
        Map<String, SkillSpec> skills = skillPoolStore.listSkills();
        List<PoolSkillSpec> result = new ArrayList<>();

        skills.forEach((name, spec) -> {
            result.add(PoolSkillSpec.builder()
                    .name(name)
                    .description(spec.getDescription())
                    .versionText(spec.getVersionText())
                    .source(spec.getSource())
                    .isProtected(spec.getIsProtected())
                    .commitText(spec.getCommitText())
                    .lastUpdated(spec.getUpdatedAt())
                    .signature(spec.getSignature())
                    .addedAt(spec.getInstalledAt())
                    .build());
        });

        return result.stream()
                .sorted(Comparator.comparing(PoolSkillSpec::getName))
                .collect(Collectors.toList());
    }

    /**
     * Download skill from pool to workspace.
     */
    public Map<String, Object> downloadFromPool(String skillName, String agentId, String targetName, boolean overwrite) {
        Path workspaceDir = dataDir.getAgentDir(agentId);
        String actualTargetName = targetName != null ? targetName : skillName;

        // Check if skill exists in pool
        if (!skillPoolStore.skillExists(skillName)) {
            return Map.of("success", false, "reason", "not_found", "message", "Skill not found in pool");
        }

        // Check if target already exists
        Path targetSkillDir = workspaceDir.resolve("skills").resolve(actualTargetName);
        if (Files.exists(targetSkillDir) && !overwrite) {
            return Map.of("success", false, "reason", "conflict", "message", "Skill already exists in workspace");
        }

        try {
            // Copy skill directory
            Path poolSkillDir = skillPoolStore.getSkillDir(skillName);
            Files.createDirectories(targetSkillDir);

            Files.walk(poolSkillDir).forEach(source -> {
                try {
                    Path dest = targetSkillDir.resolve(poolSkillDir.relativize(source));
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.copy(source, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    log.warn("Failed to copy {}: {}", source, e.getMessage());
                }
            });

            // Update manifest
            SkillSpec poolSpec = skillPoolStore.getSkill(skillName);
            updateWorkspaceManifest(workspaceDir, actualTargetName, false,
                    null, poolSpec != null ? poolSpec.getSource() : "pool");

            return Map.of("success", true, "name", actualTargetName);
        } catch (IOException e) {
            log.error("Failed to download from pool: {}", e.getMessage());
            return Map.of("success", false, "reason", "error", "message", e.getMessage());
        }
    }

    /**
     * Upload skill from workspace to pool.
     */
    public Map<String, Object> uploadToPool(String agentId, String skillName, String newName, boolean overwrite) {
        Path workspaceDir = dataDir.getAgentDir(agentId);
        String actualName = newName != null ? newName : skillName;

        // Check if skill exists in workspace
        Path skillDir = workspaceDir.resolve("skills").resolve(skillName);
        if (!Files.exists(skillDir)) {
            return Map.of("success", false, "reason", "not_found", "message", "Skill not found in workspace");
        }

        // Check if target exists in pool
        if (skillPoolStore.skillExists(actualName) && !overwrite) {
            return Map.of("success", false, "reason", "conflict", "message", "Skill already exists in pool");
        }

        try {
            // Copy to pool
            Path poolSkillDir = skillPoolStore.createSkillDir(actualName);

            Files.walk(skillDir).forEach(source -> {
                try {
                    Path dest = poolSkillDir.resolve(skillDir.relativize(source));
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.copy(source, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    log.warn("Failed to copy {}: {}", source, e.getMessage());
                }
            });

            // Update pool manifest
            SkillSpec spec = SkillSpec.builder()
                    .name(actualName)
                    .source("customized")
                    .installedAt(Instant.now().toString())
                    .build();
            skillPoolStore.addSkill(spec);

            return Map.of("success", true, "name", actualName);
        } catch (IOException e) {
            log.error("Failed to upload to pool: {}", e.getMessage());
            return Map.of("success", false, "reason", "error", "message", e.getMessage());
        }
    }

    // ==================== Hub Skills ====================

    /**
     * Search skills in hub.
     */
    public List<HubSkillSpec> searchHubSkills(String query, int limit) {
        // TODO: Implement hub search
        log.info("Search hub skills: query={}, limit={}", query, limit);
        return new ArrayList<>();
    }

    /**
     * Install skill from hub.
     */
    public HubInstallTask installFromHub(String agentId, HubInstallRequest request) {
        // TODO: Implement hub install
        log.info("Install from hub: agentId={}, url={}", agentId, request.getBundleUrl());

        return HubInstallTask.builder()
                .taskId(UUID.randomUUID().toString())
                .bundleUrl(request.getBundleUrl())
                .version(request.getVersion())
                .enable(request.getEnable())
                .overwrite(request.getOverwrite())
                .status("pending")
                .build();
    }

    /**
     * Get hub install task status.
     */
    public HubInstallTask getHubInstallTask(String taskId) {
        // TODO: Implement task tracking
        return null;
    }

    /**
     * Cancel hub install task.
     */
    public boolean cancelHubInstallTask(String taskId) {
        // TODO: Implement task cancellation
        return false;
    }

    // ==================== Builtin Skills ====================

    /**
     * List builtin import candidates.
     */
    public List<BuiltinImportSpec> listBuiltinCandidates() {
        // TODO: Implement builtin candidates listing
        return new ArrayList<>();
    }

    /**
     * Import builtin skills.
     */
    public Map<String, Object> importBuiltinSkills(ImportBuiltinRequest request) {
        // TODO: Implement builtin import
        log.info("Import builtin skills: {}", request.getSkillNames());
        return Map.of("imported", request.getSkillNames().size(), "conflicts", new ArrayList<>());
    }

    // ==================== Helper Methods ====================

    private Path getWorkspaceSkillManifestPath(Path workspaceDir) {
        return workspaceDir.resolve("skills").resolve(SKILL_MANIFEST_FILE);
    }

    private SkillInfo readSkillFromWorkspace(Path workspaceDir, String skillName, JsonNode skillNode) {
        Path skillDir = workspaceDir.resolve("skills").resolve(skillName);
        Path skillMdPath = skillDir.resolve(SKILL_MD_FILE);

        String content = "";
        if (Files.exists(skillMdPath)) {
            try {
                content = Files.readString(skillMdPath);
            } catch (IOException e) {
                log.warn("Failed to read skill content: {}", e.getMessage());
            }
        }

        return SkillInfo.builder()
                .name(skillName)
                .description(skillNode.has("description") ? skillNode.get("description").asText() : "")
                .versionText(skillNode.has("version") ? skillNode.get("version").asText() : "")
                .content(content)
                .source(skillNode.has("source") ? skillNode.get("source").asText() : "customized")
                .build();
    }

    private void updateWorkspaceManifest(Path workspaceDir, String skillName, Boolean enabled,
                                         Map<String, Object> config, String source) {
        Path manifestPath = getWorkspaceSkillManifestPath(workspaceDir);

        try {
            ObjectNode manifest;
            if (Files.exists(manifestPath)) {
                manifest = (ObjectNode) objectMapper.readTree(Files.readString(manifestPath));
            } else {
                manifest = objectMapper.createObjectNode();
                manifest.put("schema_version", "workspace-manifest.v1");
            }

            ObjectNode skillsNode = manifest.has("skills") ? (ObjectNode) manifest.get("skills")
                    : objectMapper.createObjectNode();

            ObjectNode skillNode = skillsNode.has(skillName) ? (ObjectNode) skillsNode.get(skillName)
                    : objectMapper.createObjectNode();

            skillNode.put("source", source);
            if (enabled != null) {
                skillNode.put("enabled", enabled);
            }
            if (config != null) {
                skillNode.set("config", objectMapper.valueToTree(config));
            }

            skillsNode.set(skillName, skillNode);
            manifest.set("skills", skillsNode);

            Files.createDirectories(manifestPath.getParent());
            Files.writeString(manifestPath, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(manifest));
        } catch (IOException e) {
            log.error("Failed to update workspace manifest: {}", e.getMessage());
            throw new RuntimeException("Failed to update workspace manifest", e);
        }
    }

    private void removeSkillFromManifest(Path workspaceDir, String skillName) {
        Path manifestPath = getWorkspaceSkillManifestPath(workspaceDir);

        if (!Files.exists(manifestPath)) {
            return;
        }

        try {
            ObjectNode manifest = (ObjectNode) objectMapper.readTree(Files.readString(manifestPath));

            if (manifest.has("skills")) {
                ObjectNode skillsNode = (ObjectNode) manifest.get("skills");
                skillsNode.remove(skillName);
                Files.writeString(manifestPath, objectMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(manifest));
            }
        } catch (IOException e) {
            log.error("Failed to remove skill from manifest: {}", e.getMessage());
        }
    }

    private void reconcileWorkspaceManifest(Path workspaceDir) {
        // Ensure all skills in directory are in manifest
        Path skillsDir = workspaceDir.resolve("skills");
        if (!Files.exists(skillsDir)) {
            return;
        }

        try {
            Files.list(skillsDir).forEach(skillDir -> {
                if (Files.isDirectory(skillDir)) {
                    String skillName = skillDir.getFileName().toString();
                    Path skillMdPath = skillDir.resolve(SKILL_MD_FILE);

                    if (Files.exists(skillMdPath)) {
                        updateWorkspaceManifest(workspaceDir, skillName, false, null, "customized");
                    }
                }
            });
        } catch (IOException e) {
            log.warn("Failed to reconcile workspace manifest: {}", e.getMessage());
        }
    }
}
