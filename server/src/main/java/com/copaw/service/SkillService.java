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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Service for managing skills (workspace and pool).
 */
@Service
public class SkillService {
    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    private static final String SKILL_MANIFEST_FILE = "skill.json";
    private static final String SKILL_MD_FILE = "SKILL.md";
    private static final long MAX_ZIP_BYTES = 200 * 1024 * 1024; // 200MB limit

    // Ignored artifacts when copying skills
    private static final Set<String> IGNORED_ARTIFACTS = Set.of(
            "__pycache__", "__MACOSX", ".DS_Store", "Thumbs.db", "desktop.ini"
    );

    private final CoPawDataDir dataDir;
    private final SkillPoolStore skillPoolStore;
    private final AgentConfigStore agentConfigStore;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    // Hub install task tracking
    private final ConcurrentHashMap<String, HubInstallTask> hubInstallTasks = new ConcurrentHashMap<>();

    // Hub configuration
    @Value("${copaw.skills.hub.base-url:}")
    private String hubBaseUrl;

    @Value("${copaw.skills.hub.search-path:/api/v1/search}")
    private String hubSearchPath;

    public SkillService(CoPawDataDir dataDir, SkillPoolStore skillPoolStore,
                        AgentConfigStore agentConfigStore, ObjectMapper objectMapper) {
        this.dataDir = dataDir;
        this.skillPoolStore = skillPoolStore;
        this.agentConfigStore = agentConfigStore;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .build();
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
        log.info("Import skill from ZIP for agent: {}, target: {}", agentId, targetName);

        Path workspaceDir = dataDir.getAgentDir(agentId);
        Path skillRoot = workspaceDir.resolve("skills");

        try {
            // Create temp directory for extraction
            Path tmpDir = Files.createTempDirectory("copaw_skill_upload_");
            try {
                // Extract and validate ZIP
                List<ExtractedSkill> extractedSkills = extractZipSkills(zipData, tmpDir);

                if (extractedSkills.isEmpty()) {
                    return Map.of(
                            "success", false,
                            "reason", "no_skills_found",
                            "message", "No valid skills found in uploaded zip"
                    );
                }

                // Handle target name for single skill
                if (targetName != null && !targetName.isBlank()) {
                    if (extractedSkills.size() != 1) {
                        return Map.of(
                                "success", false,
                                "reason", "invalid_target",
                                "message", "target_name is only supported for single-skill zip imports"
                        );
                    }
                    extractedSkills.get(0).targetName = normalizeSkillName(targetName);
                }

                // Check for conflicts
                Set<String> existingNames = new HashSet<>();
                if (Files.exists(skillRoot)) {
                    existingNames = Files.list(skillRoot)
                            .filter(Files::isDirectory)
                            .map(p -> p.getFileName().toString())
                            .collect(Collectors.toSet());
                }

                List<Map<String, Object>> conflicts = new ArrayList<>();
                List<ExtractedSkill> planned = new ArrayList<>();
                Set<String> seenNames = new HashSet<>();

                for (ExtractedSkill skill : extractedSkills) {
                    String skillName = skill.targetName != null ? skill.targetName : skill.name;

                    if (seenNames.contains(skillName)) {
                        conflicts.add(buildConflict(skillName, existingNames));
                        continue;
                    }
                    seenNames.add(skillName);

                    Path targetDir = skillRoot.resolve(skillName);
                    if (Files.exists(targetDir) && !overwrite) {
                        conflicts.add(buildConflict(skillName, existingNames));
                        continue;
                    }
                    planned.add(skill);
                }

                if (!conflicts.isEmpty()) {
                    return Map.of(
                            "imported", List.of(),
                            "count", 0,
                            "enabled", false,
                            "conflicts", conflicts
                    );
                }

                // Import skills
                List<String> imported = new ArrayList<>();
                Files.createDirectories(skillRoot);

                for (ExtractedSkill skill : planned) {
                    String skillName = skill.targetName != null ? skill.targetName : skill.name;
                    Path targetDir = skillRoot.resolve(skillName);

                    // Copy skill directory
                    copySkillDir(skill.sourceDir, targetDir);
                    imported.add(skillName);
                }

                // Reconcile manifest and enable if requested
                if (!imported.isEmpty()) {
                    reconcileWorkspaceManifest(workspaceDir);
                    if (enable) {
                        for (String skillName : imported) {
                            enableWorkspaceSkill(agentId, skillName);
                        }
                    }
                }

                return Map.of(
                        "imported", imported,
                        "count", imported.size(),
                        "enabled", enable && !imported.isEmpty(),
                        "conflicts", conflicts
                );

            } finally {
                // Cleanup temp directory
                deleteDirectory(tmpDir);
            }

        } catch (Exception e) {
            log.error("Failed to import skill from ZIP: {}", e.getMessage(), e);
            return Map.of(
                    "success", false,
                    "reason", "error",
                    "message", e.getMessage()
            );
        }
    }

    /**
     * Extract skills from ZIP data.
     */
    private List<ExtractedSkill> extractZipSkills(byte[] zipData, Path tmpDir) throws IOException {
        // Check if it's a valid ZIP
        if (!isZipFile(zipData)) {
            throw new IOException("Uploaded file is not a valid zip archive");
        }

        // Check size limit
        if (zipData.length > MAX_ZIP_BYTES) {
            throw new IOException("Zip file exceeds 200MB limit");
        }

        // Extract ZIP
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = tmpDir.resolve(entry.getName()).normalize();

                // Security check: ensure entry is within tmpDir
                if (!entryPath.startsWith(tmpDir)) {
                    throw new IOException("Unsafe path in zip: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(zis, entryPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }

        // Find skills in extracted content
        List<Path> realEntries = Files.list(tmpDir)
                .filter(p -> !isHidden(p.getFileName().toString()))
                .toList();

        Path extractRoot = tmpDir;
        if (realEntries.size() == 1 && Files.isDirectory(realEntries.get(0))) {
            extractRoot = realEntries.get(0);
        }

        List<ExtractedSkill> found = new ArrayList<>();

        // Check if root contains SKILL.md (single skill)
        if (Files.exists(extractRoot.resolve(SKILL_MD_FILE))) {
            String skillName = resolveSkillName(extractRoot);
            found.add(new ExtractedSkill(extractRoot, skillName, null));
        } else {
            // Look for skills in subdirectories
            try (var stream = Files.list(extractRoot)) {
                found = stream
                        .filter(p -> Files.isDirectory(p) && !isHidden(p.getFileName().toString()))
                        .filter(p -> Files.exists(p.resolve(SKILL_MD_FILE)))
                        .sorted()
                        .map(p -> new ExtractedSkill(p, resolveSkillName(p), null))
                        .collect(Collectors.toList());
            }
        }

        return found;
    }

    /**
     * Check if data is a valid ZIP file.
     */
    private boolean isZipFile(byte[] data) {
        if (data.length < 4) return false;
        // ZIP magic number: PK\x03\x04
        return data[0] == 0x50 && data[1] == 0x4B && data[2] == 0x03 && data[3] == 0x04;
    }

    /**
     * Check if name is a hidden/artifact file.
     */
    private boolean isHidden(String name) {
        return IGNORED_ARTIFACTS.contains(name) || name.startsWith(".");
    }

    /**
     * Resolve skill name from directory (read frontmatter if available).
     */
    private String resolveSkillName(Path skillDir) {
        Path skillMdPath = skillDir.resolve(SKILL_MD_FILE);
        try {
            String content = Files.readString(skillMdPath);
            // Try to extract name from YAML frontmatter
            String name = extractFrontmatterName(content);
            if (name != null && !name.isBlank()) {
                return normalizeSkillName(name);
            }
        } catch (IOException e) {
            log.warn("Failed to read SKILL.md for name resolution: {}", e.getMessage());
        }
        return skillDir.getFileName().toString();
    }

    /**
     * Extract name from YAML frontmatter.
     */
    private String extractFrontmatterName(String content) {
        if (!content.startsWith("---")) {
            return null;
        }
        int end = content.indexOf("---", 3);
        if (end == -1) {
            return null;
        }
        String frontmatter = content.substring(3, end);
        // Simple YAML parsing for name field
        for (String line : frontmatter.split("\n")) {
            line = line.trim();
            if (line.startsWith("name:") || line.startsWith("name :")) {
                int colon = line.indexOf(':');
                if (colon != -1) {
                    return line.substring(colon + 1).trim();
                }
            }
        }
        return null;
    }

    /**
     * Normalize skill name for use as directory name.
     */
    private String normalizeSkillName(String name) {
        String normalized = name.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Skill name cannot be empty");
        }
        if (normalized.contains("/") || normalized.contains("\\")) {
            // Replace path separators with dash
            normalized = normalized.replace("/", "-").replace("\\", "-");
        }
        return normalized;
    }

    /**
     * Build conflict info for a skill.
     */
    private Map<String, Object> buildConflict(String skillName, Set<String> existingNames) {
        return Map.of(
                "reason", "conflict",
                "skill_name", skillName,
                "suggested_name", suggestConflictName(skillName, existingNames)
        );
    }

    /**
     * Suggest a conflict-free name with timestamp suffix.
     */
    private String suggestConflictName(String skillName, Set<String> existingNames) {
        String base = skillName.replaceAll("-\\d{14}$", "");
        String timestamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .format(java.time.ZonedDateTime.now());
        String candidate = base + "-" + timestamp;
        if (existingNames == null || !existingNames.contains(candidate)) {
            return candidate;
        }
        // Fallback with random suffix
        return base + "-" + timestamp + "-" + System.currentTimeMillis() % 1000;
    }

    /**
     * Copy skill directory with artifact filtering.
     */
    private void copySkillDir(Path source, Path target) throws IOException {
        if (Files.exists(target)) {
            deleteDirectory(target);
        }

        Files.walk(source).forEach(srcPath -> {
            try {
                // Skip ignored artifacts
                String fileName = srcPath.getFileName().toString();
                if (IGNORED_ARTIFACTS.contains(fileName)) {
                    return;
                }

                Path destPath = target.resolve(source.relativize(srcPath));
                if (Files.isDirectory(srcPath)) {
                    Files.createDirectories(destPath);
                } else {
                    Files.createDirectories(destPath.getParent());
                    Files.copy(srcPath, destPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                log.warn("Failed to copy {}: {}", srcPath, e.getMessage());
            }
        });
    }

    /**
     * Delete directory recursively.
     */
    private void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        log.warn("Failed to delete {}: {}", path, e.getMessage());
                    }
                });
    }

    /**
     * Helper class for extracted skills.
     */
    private static class ExtractedSkill {
        final Path sourceDir;
        final String name;
        String targetName;

        ExtractedSkill(Path sourceDir, String name, String targetName) {
            this.sourceDir = sourceDir;
            this.name = name;
            this.targetName = targetName;
        }
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
     * If hub URL is not configured, returns empty list.
     */
    public List<HubSkillSpec> searchHubSkills(String query, int limit) {
        log.info("Search hub skills: query={}, limit={}", query, limit);

        if (hubBaseUrl == null || hubBaseUrl.isBlank()) {
            log.debug("Hub base URL not configured, returning empty list");
            return new ArrayList<>();
        }

        try {
            String searchUrl = hubBaseUrl.replaceAll("/$", "") + hubSearchPath;
            String url = searchUrl + "?q=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)
                    + "&limit=" + limit;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .header("User-Agent", "copaw-skills-hub/1.0")
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Hub search failed with status: {}", response.statusCode());
                return new ArrayList<>();
            }

            JsonNode data = objectMapper.readTree(response.body());
            return normalizeSearchResults(data);

        } catch (Exception e) {
            log.warn("Failed to search hub skills: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Normalize search results from hub API.
     */
    private List<HubSkillSpec> normalizeSearchResults(JsonNode data) {
        List<HubSkillSpec> results = new ArrayList<>();

        JsonNode items = data;
        // Try common response formats
        if (data.has("items")) {
            items = data.get("items");
        } else if (data.has("skills")) {
            items = data.get("skills");
        } else if (data.has("results")) {
            items = data.get("results");
        } else if (data.has("data")) {
            items = data.get("data");
        }

        if (!items.isArray()) {
            // Single item response
            if (data.has("name") || data.has("slug")) {
                items = objectMapper.createArrayNode().add(data);
            } else {
                return results;
            }
        }

        for (JsonNode item : items) {
            if (!item.isObject()) continue;

            String slug = getJsonText(item, "slug", "name");
            if (slug == null || slug.isBlank()) continue;

            String name = getJsonText(item, "name", "displayName");
            if (name == null || name.isBlank()) {
                name = slug;
            }

            results.add(HubSkillSpec.builder()
                    .slug(slug)
                    .name(name)
                    .description(getJsonText(item, "description", "summary", ""))
                    .version(getJsonText(item, "version", ""))
                    .sourceUrl(getJsonText(item, "url", "source_url", ""))
                    .build());
        }

        return results;
    }

    /**
     * Get text value from JSON node with fallback keys.
     */
    private String getJsonText(JsonNode node, String... keys) {
        for (String key : keys) {
            if (node.has(key) && !node.get(key).isNull()) {
                return node.get(key).asText();
            }
        }
        return keys.length > 0 && !keys[keys.length - 1].isEmpty() ? keys[keys.length - 1] : null;
    }

    /**
     * Install skill from hub.
     * Creates an async task and starts the installation process.
     */
    public HubInstallTask installFromHub(String agentId, HubInstallRequest request) {
        log.info("Install from hub: agentId={}, url={}", agentId, request.getBundleUrl());

        String taskId = UUID.randomUUID().toString();
        HubInstallTask task = HubInstallTask.builder()
                .taskId(taskId)
                .bundleUrl(request.getBundleUrl())
                .version(request.getVersion() != null ? request.getVersion() : "")
                .enable(request.getEnable() != null ? request.getEnable() : true)
                .overwrite(request.getOverwrite() != null ? request.getOverwrite() : false)
                .status("pending")
                .createdAt((double) System.currentTimeMillis() / 1000)
                .updatedAt((double) System.currentTimeMillis() / 1000)
                .build();

        hubInstallTasks.put(taskId, task);

        // Start async installation
        new Thread(() -> executeHubInstall(taskId, agentId, request)).start();

        return task;
    }

    /**
     * Execute hub installation in background.
     */
    private void executeHubInstall(String taskId, String agentId, HubInstallRequest request) {
        HubInstallTask task = hubInstallTasks.get(taskId);
        if (task == null) return;

        try {
            // Update status to downloading
            task.setStatus("downloading");
            task.setUpdatedAt((double) System.currentTimeMillis() / 1000);

            // Download skill bundle
            byte[] bundleData = downloadSkillBundle(request.getBundleUrl(), request.getVersion());

            // Check for cancellation
            if ("cancelled".equals(task.getStatus())) {
                return;
            }

            // Update status to installing
            task.setStatus("installing");
            task.setUpdatedAt((double) System.currentTimeMillis() / 1000);

            // Install the skill using importSkillFromZip logic
            String targetName = request.getTargetName();
            if (targetName != null && targetName.isBlank()) {
                targetName = null;
            }

            Map<String, Object> result = importSkillFromZip(
                    agentId,
                    bundleData,
                    targetName,
                    request.getOverwrite() != null ? request.getOverwrite() : false,
                    request.getEnable() != null ? request.getEnable() : true
            );

            // Check for cancellation
            if ("cancelled".equals(task.getStatus())) {
                return;
            }

            // Update task with result
            if (result.containsKey("success") && Boolean.FALSE.equals(result.get("success"))) {
                task.setStatus("failed");
                task.setError((String) result.get("message"));
            } else if (!result.containsKey("conflicts") || ((List<?>) result.get("conflicts")).isEmpty()) {
                task.setStatus("completed");
                task.setResult(result);
            } else {
                task.setStatus("conflict");
                task.setResult(result);
            }

        } catch (Exception e) {
            log.error("Hub install failed for task {}: {}", taskId, e.getMessage(), e);
            task.setStatus("failed");
            task.setError(e.getMessage());
        }

        task.setUpdatedAt((double) System.currentTimeMillis() / 1000);
    }

    /**
     * Download skill bundle from URL.
     */
    private byte[] downloadSkillBundle(String bundleUrl, String version) throws IOException {
        try {
            String url = bundleUrl;
            // Add version parameter if specified
            if (version != null && !version.isBlank()) {
                url = url + (url.contains("?") ? "&" : "?") + "version=" +
                        java.net.URLEncoder.encode(version, java.nio.charset.StandardCharsets.UTF_8);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/zip, application/octet-stream, */*")
                    .header("User-Agent", "copaw-skills-hub/1.0")
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(60))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                throw new IOException("Download failed with status: " + response.statusCode());
            }

            byte[] data = response.body();
            if (data.length > MAX_ZIP_BYTES) {
                throw new IOException("Bundle exceeds maximum size of 200MB");
            }

            return data;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
    }

    /**
     * Get hub install task status.
     */
    public HubInstallTask getHubInstallTask(String taskId) {
        return hubInstallTasks.get(taskId);
    }

    /**
     * Cancel hub install task.
     */
    public boolean cancelHubInstallTask(String taskId) {
        HubInstallTask task = hubInstallTasks.get(taskId);
        if (task == null) {
            return false;
        }

        // Can only cancel pending or downloading tasks
        String status = task.getStatus();
        if ("pending".equals(status) || "downloading".equals(status)) {
            task.setStatus("cancelled");
            task.setUpdatedAt((double) System.currentTimeMillis() / 1000);
            return true;
        }

        return false;
    }

    // ==================== Builtin Skills ====================

    /**
     * Get the builtin skills directory.
     * This mirrors the Python get_builtin_skills_dir() function.
     */
    private Path getBuiltinSkillsDir() {
        // Try to find builtin skills in the project source directory
        // First check if we're running from the project root
        Path projectRoot = Paths.get(System.getProperty("user.dir"));
        Path builtinDir = projectRoot.resolve("src/copaw/agents/skills");

        if (Files.exists(builtinDir)) {
            return builtinDir;
        }

        // Try parent directory (in case we're in server/ subdirectory)
        builtinDir = projectRoot.getParent().resolve("src/copaw/agents/skills");
        if (Files.exists(builtinDir)) {
            return builtinDir;
        }

        // Fallback: check in user's home directory under .copaw
        builtinDir = Paths.get(System.getProperty("user.home"), ".copaw", "builtin_skills");
        return builtinDir;
    }

    /**
     * Build signature (content hash) for a skill directory.
     */
    private String buildSignature(Path skillDir) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Path> files = Files.walk(skillDir)
                    .filter(Files::isRegularFile)
                    .sorted()
                    .toList();

            for (Path file : files) {
                String relPath = skillDir.relativize(file).toString();
                if (isHidden(relPath) || IGNORED_ARTIFACTS.contains(file.getFileName().toString())) {
                    continue;
                }
                digest.update(relPath.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(file));
            }

            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (Exception e) {
            log.warn("Failed to build signature for {}: {}", skillDir, e.getMessage());
            return "";
        }
    }

    /**
     * List builtin import candidates.
     */
    public List<BuiltinImportSpec> listBuiltinCandidates() {
        List<BuiltinImportSpec> candidates = new ArrayList<>();

        Path builtinDir = getBuiltinSkillsDir();
        if (!Files.exists(builtinDir)) {
            log.debug("Builtin skills directory not found: {}", builtinDir);
            return candidates;
        }

        // Get builtin signatures
        Map<String, String> builtinSigs = new HashMap<>();
        try (var stream = Files.list(builtinDir)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> Files.exists(p.resolve(SKILL_MD_FILE)))
                    .forEach(p -> builtinSigs.put(p.getFileName().toString(), buildSignature(p)));
        } catch (IOException e) {
            log.warn("Failed to list builtin skills: {}", e.getMessage());
            return candidates;
        }

        if (builtinSigs.isEmpty()) {
            return candidates;
        }

        // Get current pool skills
        SkillManifest manifest = skillPoolStore.loadManifest();
        Map<String, SkillSpec> poolSkills = manifest.getSkills() != null ?
                manifest.getSkills() : new HashMap<>();

        // Build candidates list
        for (Map.Entry<String, String> entry : builtinSigs.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            String skillName = entry.getKey();
            String sourceSignature = entry.getValue();

            // Read skill metadata
            Path skillDir = builtinDir.resolve(skillName);
            String description = "";
            String versionText = "";
            try {
                String content = Files.readString(skillDir.resolve(SKILL_MD_FILE));
                description = extractFrontmatterField(content, "description");
                versionText = extractFrontmatterField(content, "version");
                if (versionText.isEmpty()) {
                    versionText = extractFrontmatterField(content, "builtin_skill_version");
                }
            } catch (IOException e) {
                log.warn("Failed to read SKILL.md for {}: {}", skillName, e.getMessage());
            }

            // Determine status
            SkillSpec current = poolSkills.get(skillName);
            String currentSignature = current != null ? current.getSignature() : "";
            String currentSource = current != null ? current.getSource() : "";

            String status = "missing";
            if (current != null) {
                status = ("builtin".equals(currentSource) && sourceSignature.equals(currentSignature))
                        ? "current" : "conflict";
            }

            candidates.add(BuiltinImportSpec.builder()
                    .name(skillName)
                    .description(description)
                    .versionText(versionText)
                    .currentVersionText(current != null ? current.getVersionText() : "")
                    .currentSource(currentSource)
                    .status(status)
                    .build());
        }

        return candidates;
    }

    /**
     * Extract a field from YAML frontmatter.
     */
    private String extractFrontmatterField(String content, String fieldName) {
        if (!content.startsWith("---")) {
            return "";
        }
        int end = content.indexOf("---", 3);
        if (end == -1) {
            return "";
        }
        String frontmatter = content.substring(3, end);
        String prefix = fieldName + ":";
        for (String line : frontmatter.split("\n")) {
            line = line.trim();
            if (line.startsWith(prefix) || line.startsWith(fieldName + " :")) {
                int colon = line.indexOf(':');
                if (colon != -1) {
                    return line.substring(colon + 1).trim();
                }
            }
        }
        return "";
    }

    /**
     * Import builtin skills.
     */
    public Map<String, Object> importBuiltinSkills(ImportBuiltinRequest request) {
        log.info("Import builtin skills: {}", request.getSkillNames());

        Path builtinDir = getBuiltinSkillsDir();
        Path poolDir = skillPoolStore.getSkillPoolDir();

        try {
            Files.createDirectories(poolDir);
        } catch (IOException e) {
            log.error("Failed to create pool directory: {}", e.getMessage());
            return Map.of(
                    "imported", List.of(),
                    "updated", List.of(),
                    "unchanged", List.of(),
                    "conflicts", List.of()
            );
        }

        // Get candidates
        List<BuiltinImportSpec> candidates = listBuiltinCandidates();
        Map<String, BuiltinImportSpec> candidateMap = candidates.stream()
                .collect(Collectors.toMap(BuiltinImportSpec::getName, c -> c));

        List<String> selectedNames = request.getSkillNames() != null ?
                request.getSkillNames() : new ArrayList<>(candidateMap.keySet());

        // Check for unknown skills
        List<String> unknown = selectedNames.stream()
                .filter(n -> !candidateMap.containsKey(n))
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown builtin skill(s): " + String.join(", ", unknown));
        }

        // Check for conflicts
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (String name : selectedNames) {
            BuiltinImportSpec candidate = candidateMap.get(name);
            if ("conflict".equals(candidate.getStatus())) {
                conflicts.add(Map.of(
                        "skill_name", name,
                        "source_version_text", candidate.getVersionText(),
                        "current_version_text", candidate.getCurrentVersionText(),
                        "current_source", candidate.getCurrentSource()
                ));
            }
        }

        if (!conflicts.isEmpty() && !Boolean.TRUE.equals(request.getOverwriteConflicts())) {
            return Map.of(
                    "imported", List.of(),
                    "updated", List.of(),
                    "unchanged", List.of(),
                    "conflicts", conflicts
            );
        }

        // Build builtin signatures
        Map<String, String> builtinSigs = new HashMap<>();
        for (String name : selectedNames) {
            Path skillDir = builtinDir.resolve(name);
            builtinSigs.put(name, buildSignature(skillDir));
        }

        List<String> imported = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();

        // Process each skill
        SkillManifest manifest = skillPoolStore.loadManifest();
        if (manifest.getSkills() == null) {
            manifest.setSkills(new HashMap<>());
        }
        Map<String, SkillSpec> skills = manifest.getSkills();

        // Update builtin_skill_names in manifest
        if (manifest.getBuiltinSkillNames() == null) {
            manifest.setBuiltinSkillNames(new ArrayList<>());
        }
        Set<String> builtinNames = new HashSet<>(manifest.getBuiltinSkillNames());
        builtinNames.addAll(builtinSigs.keySet());
        manifest.setBuiltinSkillNames(new ArrayList<>(builtinNames));

        for (String skillName : selectedNames) {
            Path skillDir = builtinDir.resolve(skillName);
            Path target = poolDir.resolve(skillName);

            SkillSpec existing = skills.get(skillName);
            String sourceSignature = builtinSigs.getOrDefault(skillName, "");
            String currentSignature = "";
            if (Files.exists(target)) {
                currentSignature = buildSignature(target);
            }

            try {
                if (!Files.exists(target)) {
                    copySkillDir(skillDir, target);
                    imported.add(skillName);
                } else if (!sourceSignature.equals(currentSignature)) {
                    copySkillDir(skillDir, target);
                    updated.add(skillName);
                } else {
                    unchanged.add(skillName);
                }

                // Update manifest entry
                SkillSpec spec = buildSkillSpec(skillName, target, "builtin");
                skills.put(skillName, spec);

            } catch (IOException e) {
                log.error("Failed to import builtin skill {}: {}", skillName, e.getMessage());
            }
        }

        // Save manifest
        skillPoolStore.saveManifest(manifest);

        return Map.of(
                "imported", imported,
                "updated", updated,
                "unchanged", unchanged,
                "conflicts", conflicts
        );
    }

    /**
     * Build SkillSpec from skill directory.
     */
    private SkillSpec buildSkillSpec(String skillName, Path skillDir, String source) {
        String description = "";
        String versionText = "";
        try {
            String content = Files.readString(skillDir.resolve(SKILL_MD_FILE));
            description = extractFrontmatterField(content, "description");
            versionText = extractFrontmatterField(content, "version");
            if (versionText.isEmpty()) {
                versionText = extractFrontmatterField(content, "builtin_skill_version");
            }
        } catch (IOException e) {
            log.warn("Failed to read SKILL.md for {}: {}", skillName, e.getMessage());
        }

        return SkillSpec.builder()
                .name(skillName)
                .description(description)
                .versionText(versionText)
                .source(source)
                .signature(buildSignature(skillDir))
                .installedAt(Instant.now().toString())
                .updatedAt(Instant.now().toString())
                .build();
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
