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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

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

    /**
     * Ensure the skill pool is initialized on startup.
     * Mirrors Python ensure_skill_pool_initialized().
     */
    @PostConstruct
    public void ensureSkillPoolInitialized() {
        // First, ensure builtin skills are extracted from classpath resources
        extractBuiltinSkillsFromClasspath();

        Path poolDir = skillPoolStore.getSkillPoolDir();
        boolean created = false;

        try {
            if (!Files.exists(poolDir)) {
                Files.createDirectories(poolDir);
                created = true;
            }

            Path manifestPath = poolDir.resolve(SKILL_MANIFEST_FILE);
            if (!Files.exists(manifestPath)) {
                SkillManifest emptyManifest = SkillManifest.builder()
                        .schemaVersion("skill-pool-manifest.v1")
                        .build();
                skillPoolStore.saveManifest(emptyManifest);
                created = true;
            }

            // Import builtins on first creation OR if pool has no skills
            if (created || skillPoolStore.listSkills().isEmpty()) {
                ImportBuiltinRequest request = new ImportBuiltinRequest();
                request.setOverwriteConflicts(false);
                importBuiltinSkills(request);
            }

            // Always reconcile pool manifest to sync with current builtins
            reconcilePoolManifest();

        } catch (IOException e) {
            log.error("Failed to initialize skill pool: {}", e.getMessage());
        }
    }

    /**
     * Reconcile pool manifest with the filesystem.
     * Mirrors Python reconcile_pool_manifest().
     * Scans the pool directory and rebuilds metadata from discovered skills.
     */
    public List<PoolSkillSpec> reconcilePoolManifest() {
        Path poolDir = skillPoolStore.getSkillPoolDir();
        try {
            Files.createDirectories(poolDir);
        } catch (IOException e) {
            log.error("Failed to create pool directory: {}", e.getMessage());
            return listPoolSkills();
        }

        // Get builtin signatures
        Map<String, String> builtinSigs = getBuiltinSignatures();
        List<String> builtinNames = new ArrayList<>(builtinSigs.keySet());
        Collections.sort(builtinNames);

        // Discover skills on disk
        Map<String, Path> discovered = new TreeMap<>();
        try (Stream<Path> stream = Files.list(poolDir)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> Files.exists(p.resolve(SKILL_MD_FILE)))
                    .forEach(p -> discovered.put(p.getFileName().toString(), p));
        } catch (IOException e) {
            log.warn("Failed to list pool directory: {}", e.getMessage());
            return listPoolSkills();
        }

        // Load current manifest
        SkillManifest manifest = skillPoolStore.loadManifest();
        if (manifest.getSkills() == null) {
            manifest.setSkills(new HashMap<>());
        }
        Map<String, SkillSpec> skills = manifest.getSkills();

        // Update builtin_skill_names
        manifest.setBuiltinSkillNames(builtinNames);

        // Update metadata for discovered skills
        for (Map.Entry<String, Path> entry : discovered.entrySet()) {
            String skillName = entry.getKey();
            Path skillDir = entry.getValue();
            SkillSpec existing = skills.get(skillName);

            // Classify source
            String source = classifyPoolSkillSource(skillName, existing, builtinNames, builtinSigs, skillDir);

            // Build updated spec
            SkillSpec spec = buildSkillSpec(skillName, skillDir, source);

            // Preserve config from existing entry if present
            if (existing != null && existing.getRequirements() != null) {
                spec.setRequirements(existing.getRequirements());
            }

            skills.put(skillName, spec);
        }

        // Remove entries that no longer exist on disk
        skills.keySet().removeIf(name -> !discovered.containsKey(name));

        // Save updated manifest
        skillPoolStore.saveManifest(manifest);

        return listPoolSkills();
    }

    /**
     * Get cached builtin signatures.
     */
    private Map<String, String> getBuiltinSignatures() {
        Map<String, String> sigs = new HashMap<>();
        Path builtinDir = getBuiltinSkillsDir();
        if (!Files.exists(builtinDir)) {
            return sigs;
        }
        try (Stream<Path> stream = Files.list(builtinDir)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> Files.exists(p.resolve(SKILL_MD_FILE)))
                    .forEach(p -> sigs.put(p.getFileName().toString(), buildSignature(p)));
        } catch (IOException e) {
            log.warn("Failed to list builtin skills: {}", e.getMessage());
        }
        return sigs;
    }

    /**
     * Classify a pool skill's source against packaged builtins.
     * Mirrors Python _classify_pool_skill_source().
     */
    private String classifyPoolSkillSource(String skillName, SkillSpec existing,
                                            List<String> builtinNames,
                                            Map<String, String> builtinSigs,
                                            Path skillDir) {
        if (!builtinNames.contains(skillName)) {
            return "customized";
        }
        if (!builtinSigs.containsKey(skillName)) {
            return "customized";
        }
        if (existing != null) {
            if ("builtin".equals(existing.getSource())) {
                return "builtin";
            }
            return "customized";
        }
        // New skill - compare signatures
        String poolSig = buildSignature(skillDir);
        String builtinSig = builtinSigs.getOrDefault(skillName, "");
        return poolSig.equals(builtinSig) ? "builtin" : "customized";
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
     * List all pool skills (with content from SKILL.md).
     */
    public List<PoolSkillSpec> listPoolSkills() {
        Map<String, SkillSpec> skills = skillPoolStore.listSkills();
        List<PoolSkillSpec> result = new ArrayList<>();

        skills.forEach((name, spec) -> {
            // Read SKILL.md content for each skill
            String content = skillPoolStore.readSkillContent(name);
            result.add(PoolSkillSpec.builder()
                    .name(name)
                    .description(spec.getDescription())
                    .versionText(spec.getVersionText())
                    .content(content)
                    .source(spec.getSource())
                    .isProtected(spec.getIsProtected())
                    .commitText(spec.getCommitText())
                    .config(spec.getConfig())
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
     * Get a specific pool skill spec.
     */
    public SkillSpec getPoolSkill(String skillName) {
        return skillPoolStore.getSkill(skillName);
    }

    /**
     * Update pool skill config in manifest.
     */
    public void updatePoolSkillConfig(String skillName, Map<String, Object> config) {
        SkillSpec spec = skillPoolStore.getSkill(skillName);
        if (spec == null) {
            throw new IllegalArgumentException("Pool skill not found: " + skillName);
        }
        spec.setConfig(config);
        skillPoolStore.addSkill(spec);
    }

    /**
     * Delete a skill from the pool.
     */
    public boolean deletePoolSkill(String skillName) {
        boolean removedFromManifest = skillPoolStore.removeSkill(skillName);
        boolean removedDir = skillPoolStore.deleteSkillDir(skillName);
        return removedFromManifest || removedDir;
    }

    /**
     * Batch delete skills from the pool.
     */
    public Map<String, Map<String, Object>> batchDeletePoolSkills(List<String> skillNames) {
        Map<String, Map<String, Object>> results = new LinkedHashMap<>();
        for (String skillName : skillNames) {
            try {
                boolean deleted = deletePoolSkill(skillName);
                Map<String, Object> r = new HashMap<>();
                r.put("success", deleted);
                if (!deleted) r.put("reason", "delete_failed");
                results.put(skillName, r);
            } catch (Exception e) {
                Map<String, Object> r = new HashMap<>();
                r.put("success", false);
                r.put("reason", e.getMessage());
                results.put(skillName, r);
            }
        }
        return results;
    }

    /**
     * Create a skill in the pool.
     * Mirrors Python POST /pool/create.
     */
    public Map<String, Object> createPoolSkill(CreateSkillRequest request) {
        String name = normalizeSkillName(request.getName());
        Path poolDir = skillPoolStore.getSkillPoolDir();
        Path skillDir = poolDir.resolve(name);

        if (Files.exists(skillDir)) {
            return Map.of(
                    "created", false,
                    "reason", "conflict",
                    "suggested_name", suggestConflictName(name, null)
            );
        }

        try {
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve(SKILL_MD_FILE),
                    request.getContent() != null ? request.getContent() : "");

            SkillSpec spec = buildSkillSpec(name, skillDir, "customized");
            if (request.getConfig() != null) {
                spec.setConfig(request.getConfig());
            }
            skillPoolStore.addSkill(spec);

            return Map.of("created", true, "name", name);
        } catch (IOException e) {
            log.error("Failed to create pool skill: {}", e.getMessage());
            throw new RuntimeException("Failed to create pool skill", e);
        }
    }

    /**
     * Save/edit a pool skill.
     * Mirrors Python PUT /pool/save.
     */
    public Map<String, Object> savePoolSkill(SaveSkillRequest request) {
        String sourceName = request.getSourceName() != null ? request.getSourceName() : request.getName();
        String targetName = normalizeSkillName(request.getName());
        Path poolDir = skillPoolStore.getSkillPoolDir();
        Path sourceDir = poolDir.resolve(sourceName);

        if (!Files.exists(sourceDir) || !Files.exists(sourceDir.resolve(SKILL_MD_FILE))) {
            return Map.of("success", false, "reason", "not_found");
        }

        boolean isRename = !sourceName.equals(targetName);
        Path targetDir = isRename ? poolDir.resolve(targetName) : sourceDir;

        if (isRename && Files.exists(targetDir)) {
            return Map.of(
                    "success", false,
                    "reason", "conflict",
                    "suggested_name", suggestConflictName(targetName, null)
            );
        }

        try {
            if (isRename) {
                copySkillDir(sourceDir, targetDir);
                deleteDirectory(sourceDir);
                skillPoolStore.removeSkill(sourceName);
            }

            // Write SKILL.md content
            if (request.getContent() != null) {
                Files.writeString(targetDir.resolve(SKILL_MD_FILE), request.getContent());
            }

            // Update manifest
            SkillSpec spec = buildSkillSpec(targetName, targetDir, "customized");
            if (request.getConfig() != null) {
                spec.setConfig(request.getConfig());
            }
            skillPoolStore.addSkill(spec);

            return Map.of(
                    "success", true,
                    "mode", isRename ? "rename" : "edit",
                    "name", targetName
            );
        } catch (IOException e) {
            log.error("Failed to save pool skill: {}", e.getMessage());
            throw new RuntimeException("Failed to save pool skill", e);
        }
    }

    /**
     * Update a single builtin skill to its latest version.
     * Mirrors Python POST /pool/{skill_name}/update-builtin.
     */
    public Map<String, Object> updateSingleBuiltin(String skillName) {
        Path builtinDir = getBuiltinSkillsDir();
        Path sourceSkillDir = builtinDir.resolve(skillName);
        if (!Files.exists(sourceSkillDir) || !Files.exists(sourceSkillDir.resolve(SKILL_MD_FILE))) {
            throw new IllegalArgumentException("Not a builtin skill: " + skillName);
        }

        Path poolDir = skillPoolStore.getSkillPoolDir();
        Path targetDir = poolDir.resolve(skillName);

        try {
            copySkillDir(sourceSkillDir, targetDir);
            SkillSpec spec = buildSkillSpec(skillName, targetDir, "builtin");
            skillPoolStore.addSkill(spec);
            return Map.of("updated", true, "name", skillName);
        } catch (IOException e) {
            log.error("Failed to update builtin skill: {}", e.getMessage());
            throw new RuntimeException("Failed to update builtin", e);
        }
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

            // Resolve the bundle via GitHub API (same logic as pool import)
            String version = request.getVersion() != null ? request.getVersion() : "";
            Map<String, Object> bundle = resolveHubBundle(request.getBundleUrl(), version);

            if ("cancelled".equals(task.getStatus())) return;

            // Update status to installing
            task.setStatus("installing");
            task.setUpdatedAt((double) System.currentTimeMillis() / 1000);

            // Extract name and content from bundle
            String skillName = normalizeHubBundleName(bundle, request.getBundleUrl());
            String content = normalizeHubBundleContent(bundle);
            @SuppressWarnings("unchecked")
            Map<String, String> files = (Map<String, String>) bundle.getOrDefault("files", Map.of());

            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("Hub bundle missing SKILL.md content");
            }

            // Apply target_name override
            String targetName = request.getTargetName();
            if (targetName != null && !targetName.isBlank()) {
                skillName = normalizeSkillName(targetName.trim());
            }

            // Write skill to workspace
            Path workspaceDir = dataDir.getAgentDir(agentId);
            Path skillsDir = workspaceDir.resolve("skills").resolve(skillName);
            boolean overwrite = request.getOverwrite() != null ? request.getOverwrite() : false;
            boolean enable = request.getEnable() != null ? request.getEnable() : true;

            if (Files.exists(skillsDir) && !overwrite) {
                task.setStatus("conflict");
                task.setResult(Map.of("conflicts", List.of(
                        Map.of("reason", "exists", "skill_name", skillName,
                                "suggested_name", suggestConflictName(skillName, null)))));
                task.setUpdatedAt((double) System.currentTimeMillis() / 1000);
                return;
            }

            Files.createDirectories(skillsDir);
            Files.writeString(skillsDir.resolve(SKILL_MD_FILE), content);
            for (Map.Entry<String, String> entry : files.entrySet()) {
                if ("SKILL.md".equals(entry.getKey())) continue;
                Path filePath = skillsDir.resolve(entry.getKey());
                Files.createDirectories(filePath.getParent());
                Files.writeString(filePath, entry.getValue());
            }

            if ("cancelled".equals(task.getStatus())) return;

            // Update workspace manifest
            updateWorkspaceManifest(
                    dataDir.getAgentDir(agentId), skillName, enable, null, "customized");

            task.setStatus("completed");
            task.setResult(Map.of(
                    "imported", List.of(skillName),
                    "count", 1,
                    "enabled", enable
            ));

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

    // ==================== Pool Import from Hub ====================

    /**
     * Import a skill from a hub URL directly into the skill pool.
     * Resolves skills.sh / GitHub URLs via the GitHub Contents API,
     * mirrors Python import_pool_skill_from_hub().
     */
    public Map<String, Object> importPoolSkillFromHub(HubInstallRequest request) {
        String bundleUrl = request.getBundleUrl();
        if (bundleUrl == null || bundleUrl.isBlank()) {
            throw new IllegalArgumentException("bundle_url must be a valid http(s) URL");
        }
        if (!bundleUrl.startsWith("http://") && !bundleUrl.startsWith("https://")) {
            throw new IllegalArgumentException("bundle_url must be a valid http(s) URL");
        }

        try {
            // 1. Resolve the bundle (JSON with name, content, files, etc.)
            String version = request.getVersion() != null ? request.getVersion() : "";
            Map<String, Object> bundle = resolveHubBundle(bundleUrl, version);
            String sourceUrl = (String) bundle.getOrDefault("source_url", bundleUrl);

            // 2. Normalize: extract name, content, files from the bundle
            String skillName = normalizeHubBundleName(bundle, bundleUrl);
            String content = normalizeHubBundleContent(bundle);
            @SuppressWarnings("unchecked")
            Map<String, String> files = (Map<String, String>) bundle.getOrDefault("files", Map.of());

            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("Hub bundle missing SKILL.md content");
            }

            // Apply target_name override
            String normalizedTarget = request.getTargetName() != null ? request.getTargetName().trim() : "";
            if (!normalizedTarget.isEmpty()) {
                skillName = normalizeSkillName(normalizedTarget);
            }

            // 3. Write to pool directory
            Path poolDir = skillPoolStore.getSkillPoolDir();
            Path targetDir = poolDir.resolve(skillName);
            if (Files.exists(targetDir) && !Boolean.TRUE.equals(request.getOverwrite())) {
                throw new RuntimeException("Skill '" + skillName + "' already exists in pool. Use overwrite=true to replace.");
            }

            Files.createDirectories(targetDir);
            // Write SKILL.md
            Files.writeString(targetDir.resolve(SKILL_MD_FILE), content);
            // Write extra files (references, scripts, etc.)
            for (Map.Entry<String, String> entry : files.entrySet()) {
                String relPath = entry.getKey();
                if ("SKILL.md".equals(relPath)) continue;
                Path filePath = targetDir.resolve(relPath);
                Files.createDirectories(filePath.getParent());
                Files.writeString(filePath, entry.getValue());
            }

            // 4. Update manifest
            SkillSpec spec = buildSkillSpec(skillName, targetDir, "customized");
            skillPoolStore.addSkill(spec);

            return Map.of(
                    "installed", true,
                    "name", skillName,
                    "enabled", false,
                    "source_url", sourceUrl
            );
        } catch (IOException e) {
            String msg = e.getMessage();
            log.error("Failed to import pool skill from hub: {}", msg, e);
            if (msg != null && (msg.contains("SSLHandshake") || msg.contains("handshake")
                    || msg.contains("Connection refused") || msg.contains("Connection timed out")
                    || msg.contains("UnknownHost") || msg.contains("unreachable"))) {
                throw new RuntimeException(
                        "Network error while importing skill from hub. "
                        + "The GitHub API (api.github.com) or raw content server (raw.githubusercontent.com) "
                        + "may be unreachable in your network environment. "
                        + "Please check your network connection or proxy settings.", e);
            }
            throw new RuntimeException("Failed to import skill from hub: " + msg, e);
        }
    }

    // ---------- Hub bundle resolution helpers ----------

    /**
     * Resolve a hub bundle URL to a JSON-like map containing
     * {name, content/skill_md, files, ...}.
     * Handles skills.sh and GitHub URLs via the GitHub Contents API;
     * falls back to a direct JSON GET for other URLs.
     */
    private Map<String, Object> resolveHubBundle(String bundleUrl, String version) throws IOException {
        // Try skills.sh URL  (e.g. https://skills.sh/owner/repo/skill)
        String[] skillsShSpec = extractSkillsShSpec(bundleUrl);
        if (skillsShSpec != null) {
            return fetchBundleFromGitHubSkill(
                    skillsShSpec[0], skillsShSpec[1], skillsShSpec[2], version, null);
        }
        // Try direct GitHub URL (e.g. https://github.com/owner/repo/tree/main/skills/x)
        String[] ghSpec = extractGitHubSpec(bundleUrl);
        if (ghSpec != null) {
            // ghSpec may contain an explicit branch at index 3
            String branchOverride = ghSpec.length > 3 ? ghSpec[3] : null;
            String ver = (version != null && !version.isBlank()) ? version : branchOverride;
            return fetchBundleFromGitHubSkill(ghSpec[0], ghSpec[1], ghSpec[2], ver,
                    branchOverride);
        }
        // Fallback: treat URL as a direct JSON bundle endpoint
        return fetchJsonBundle(bundleUrl);
    }

    /**
     * Parse a skills.sh URL into [owner, repo, skill].
     * URL pattern: https://skills.sh/{owner}/{repo}/{skill}
     */
    private String[] extractSkillsShSpec(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";
            if (!"skills.sh".equals(host) && !"www.skills.sh".equals(host)) return null;
            String[] parts = uri.getPath().split("/");
            List<String> segments = new ArrayList<>();
            for (String p : parts) { if (!p.isEmpty()) segments.add(p); }
            if (segments.size() < 3) return null;
            return new String[]{ segments.get(0), segments.get(1), segments.get(2) };
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parse a GitHub URL into [owner, repo, skillHint, branch].
     * Supports: https://github.com/{owner}/{repo}/tree/{branch}/path/to/skill
     *           https://github.com/{owner}/{repo}
     * Returns [owner, repo, skillHint] where skillHint is the full path after branch,
     * or [owner, repo, skillHint, branch] when branch is explicitly present.
     */
    private String[] extractGitHubSpec(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";
            if (!"github.com".equals(host) && !"www.github.com".equals(host)) return null;
            String[] parts = uri.getPath().split("/");
            List<String> segments = new ArrayList<>();
            for (String p : parts) { if (!p.isEmpty()) segments.add(p); }
            if (segments.size() < 2) return null;
            String owner = segments.get(0);
            String repo = segments.get(1);
            // Extract full path after /tree/{branch}/...
            // e.g. /tree/master/skills/dev/solutions/my-skill → branch=master, hint=skills/dev/solutions/my-skill
            if (segments.size() >= 5 && "tree".equals(segments.get(2))) {
                String branch = segments.get(3);
                // Join everything after the branch as the path hint
                String fullPath = String.join("/", segments.subList(4, segments.size()));
                return new String[]{ owner, repo, fullPath, branch };
            }
            if (segments.size() >= 4 && "tree".equals(segments.get(2))) {
                // /tree/{branch} only, no deeper path
                return new String[]{ owner, repo, "", segments.get(3) };
            }
            return new String[]{ owner, repo, "" };
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Fetch a skill bundle from a GitHub repo using the Contents API.
     * Mirrors Python _fetch_bundle_from_skills_sh_url + _fetch_bundle_from_repo_and_skill_hint.
     */
    private Map<String, Object> fetchBundleFromGitHubSkill(
            String owner, String repo, String skillHint, String version,
            String branchOverride) throws IOException {

        // Determine branch candidates
        String defaultBranch = githubGetDefaultBranch(owner, repo);
        List<String> branches = new ArrayList<>();
        if (version != null && !version.isBlank()) {
            branches.add(version.trim());
        }
        if (branchOverride != null && !branchOverride.isBlank()
                && !branches.contains(branchOverride)) {
            branches.add(branchOverride);
        }
        if (!branches.contains(defaultBranch)) {
            branches.add(defaultBranch);
        }
        for (String b : new String[]{"main", "master"}) {
            if (!branches.contains(b)) branches.add(b);
        }

        String skill = skillHint.trim();
        String selectedRoot = "";
        JsonNode skillMdEntry = null;
        String foundBranch = branches.get(0);

        // Search for SKILL.md in candidate paths and branches
        for (String branch : branches) {
            foundBranch = branch;
            // Build candidate roots: the full path first (most specific),
            // then "skills/{lastSegment}", then just lastSegment, then repo root
            List<String> roots = new ArrayList<>();
            if (!skill.isEmpty()) {
                roots.add(skill);  // full path e.g. "skills/developertools/solutions/my-skill"
                // Also try prefixing with "skills/" if not already
                if (!skill.startsWith("skills/")) {
                    roots.add("skills/" + skill);
                }
                // Also try just the last segment (for skills.sh style)
                String lastSegment = skill.contains("/") ? skill.substring(skill.lastIndexOf('/') + 1) : skill;
                if (!lastSegment.equals(skill)) {
                    roots.add("skills/" + lastSegment);
                    roots.add(lastSegment);
                }
            }
            roots.add(""); // repo root fallback

            for (String root : roots) {
                String skillMdPath = root.isEmpty() ? "SKILL.md" : root + "/SKILL.md";
                JsonNode entry = githubGetContentEntry(owner, repo, skillMdPath, branch);
                if (entry != null && "file".equals(nodeText(entry, "type"))) {
                    selectedRoot = root;
                    skillMdEntry = entry;
                    break;
                }
            }
            if (skillMdEntry != null) break;
        }

        if (skillMdEntry == null) {
            throw new IOException(
                    "Could not find SKILL.md in https://github.com/" + owner + "/" + repo
                            + ". Path hint: '" + skillHint + "'; tried branches: " + branches);
        }

        // Read SKILL.md content (with fallback for network-restricted environments)
        String skillMdPath = selectedRoot.isEmpty() ? "SKILL.md" : selectedRoot + "/SKILL.md";
        String skillMdContent = githubReadFileWithFallback(
                owner, repo, skillMdPath, foundBranch, skillMdEntry);

        // Collect all files in the skill directory tree
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", skillMdContent);
        files.putAll(githubCollectTreeFiles(owner, repo, foundBranch, selectedRoot));

        // Build result bundle – use the last segment of the path as the skill name
        String skillName = skill.isEmpty() ? repo : 
                (skill.contains("/") ? skill.substring(skill.lastIndexOf('/') + 1) : skill);
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("name", skillName);
        bundle.put("content", skillMdContent);
        bundle.put("files", files);
        bundle.put("source_url", "https://github.com/" + owner + "/" + repo);
        return bundle;
    }

    // ---------- GitHub API helpers ----------

    private String githubGetDefaultBranch(String owner, String repo) {
        try {
            String url = "https://api.github.com/repos/" + owner + "/" + repo;
            JsonNode meta = httpGetJson(url);
            String branch = nodeText(meta, "default_branch");
            return (branch != null && !branch.isBlank()) ? branch : "main";
        } catch (Exception e) {
            log.debug("Could not fetch default branch for {}/{}: {}", owner, repo, e.getMessage());
            return "main";
        }
    }

    /**
     * Get a single content entry from GitHub Contents API.
     * Returns null on 404.
     */
    private JsonNode githubGetContentEntry(String owner, String repo, String path, String ref) {
        try {
            String encodedPath = java.net.URLEncoder.encode(path, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("%2F", "/");
            String url = "https://api.github.com/repos/" + owner + "/" + repo
                    + "/contents/" + encodedPath + "?ref=" + ref;
            return httpGetJson(url);
        } catch (Exception e) {
            return null; // 404 or other error
        }
    }

    private String githubReadFile(JsonNode entry) throws IOException {
        // Try download_url first (raw.githubusercontent.com), but may fail
        // in restricted network environments (e.g. SSL handshake failure).
        String downloadUrl = nodeText(entry, "download_url");
        if (downloadUrl != null && !downloadUrl.isEmpty()) {
            try {
                return httpGetText(downloadUrl);
            } catch (IOException e) {
                log.debug("download_url failed ({}), falling back to base64 content", e.getMessage());
            }
        }
        // Fall back to base64 content embedded in the API response
        String b64 = nodeText(entry, "content");
        if (b64 != null && !b64.isEmpty()) {
            return new String(Base64.getMimeDecoder().decode(b64), java.nio.charset.StandardCharsets.UTF_8);
        }
        throw new IOException(
                "Unable to read file content from GitHub. "
                + "This may be caused by network restrictions (e.g. raw.githubusercontent.com is unreachable). "
                + "Please check your network connection or proxy settings.");
    }

    /**
     * Collect all files under a directory root in a GitHub repo.
     * For files where download_url is unreachable, fetches content via
     * the Contents API (which includes base64 content for files < 1MB).
     */
    private Map<String, String> githubCollectTreeFiles(
            String owner, String repo, String ref, String root) {
        Map<String, String> files = new LinkedHashMap<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.add(root);
        int maxFiles = 200;

        while (!pending.isEmpty() && files.size() < maxFiles) {
            String dir = pending.poll();
            try {
                String encodedDir = dir.isEmpty() ? ""
                        : java.net.URLEncoder.encode(dir, java.nio.charset.StandardCharsets.UTF_8)
                        .replace("%2F", "/");
                String url = "https://api.github.com/repos/" + owner + "/" + repo
                        + "/contents" + (encodedDir.isEmpty() ? "" : "/" + encodedDir)
                        + "?ref=" + ref;
                JsonNode entries = httpGetJson(url);
                if (entries == null || !entries.isArray()) continue;

                for (JsonNode entry : entries) {
                    String type = nodeText(entry, "type");
                    String entryPath = nodeText(entry, "path");
                    if (entryPath == null || entryPath.isEmpty()) continue;

                    if ("dir".equals(type)) {
                        pending.add(entryPath);
                    } else if ("file".equals(type)) {
                        // Compute relative path from root
                        String rel = root.isEmpty() ? entryPath
                                : entryPath.startsWith(root + "/")
                                ? entryPath.substring(root.length() + 1)
                                : entryPath;
                        if ("SKILL.md".equals(rel)) continue; // already read
                        try {
                            // Directory listing entries usually lack "content" field;
                            // githubReadFile will try download_url first, then base64.
                            // If download_url fails and no content, fetch individually.
                            files.put(rel, githubReadFileWithFallback(owner, repo, entryPath, ref, entry));
                        } catch (IOException e) {
                            log.debug("Skipped unreadable file: {}", entryPath);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Error listing dir '{}': {}", dir, e.getMessage());
            }
        }
        return files;
    }

    /**
     * Read file content with full fallback chain:
     * 1. download_url (fast, raw content)
     * 2. base64 content in the entry (if present)
     * 3. Re-fetch via Contents API individually (gets base64 content)
     */
    private String githubReadFileWithFallback(
            String owner, String repo, String path, String ref, JsonNode entry) throws IOException {
        try {
            return githubReadFile(entry);
        } catch (IOException e) {
            // Last resort: fetch the file individually via Contents API to get base64 content
            log.debug("Fetching '{}' individually via Contents API", path);
            JsonNode freshEntry = githubGetContentEntry(owner, repo, path, ref);
            if (freshEntry != null) {
                return githubReadFile(freshEntry);
            }
            throw e;
        }
    }

    // ---------- Generic HTTP helpers ----------

    private JsonNode httpGetJson(String url) throws IOException {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .header("User-Agent", "copaw-skills-hub/1.0")
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(30))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IOException("HTTP " + resp.statusCode() + " for " + url);
            }
            return objectMapper.readTree(resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    private String httpGetText(String url) throws IOException {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "copaw-skills-hub/1.0")
                    .GET()
                    .timeout(java.time.Duration.ofSeconds(30))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IOException("HTTP " + resp.statusCode() + " for " + url);
            }
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchJsonBundle(String url) throws IOException {
        JsonNode node = httpGetJson(url);
        return objectMapper.convertValue(node, Map.class);
    }

    private static String nodeText(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        return node.get(field).asText();
    }

    // ---------- Hub bundle normalization ----------

    /**
     * Extract skill name from a hub bundle.
     * Checks bundle["name"], bundle["skill"]["name"], then frontmatter.
     */
    private String normalizeHubBundleName(Map<String, Object> bundle, String bundleUrl) {
        // Direct name
        String name = stringOrNull(bundle.get("name"));
        // Nested under "skill" key
        if (name == null || name.isBlank()) {
            Object skillObj = bundle.get("skill");
            if (skillObj instanceof Map) {
                name = stringOrNull(((Map<?, ?>) skillObj).get("name"));
            }
        }
        // Try frontmatter in content
        if (name == null || name.isBlank()) {
            String content = normalizeHubBundleContent(bundle);
            if (content != null) {
                name = extractFrontmatterName(content);
            }
        }
        // Fallback: last segment of URL path
        if (name == null || name.isBlank()) {
            try {
                String path = URI.create(bundleUrl).getPath().replaceAll("/$", "");
                String[] parts = path.split("/");
                name = parts[parts.length - 1];
            } catch (Exception ignored) {
                name = "imported_skill";
            }
        }
        return normalizeSkillName(name);
    }

    /**
     * Extract SKILL.md content from a hub bundle.
     */
    private String normalizeHubBundleContent(Map<String, Object> bundle) {
        // Direct content
        String content = stringOrNull(bundle.get("content"));
        if (content != null && !content.isBlank()) return content;
        content = stringOrNull(bundle.get("skill_md"));
        if (content != null && !content.isBlank()) return content;
        content = stringOrNull(bundle.get("skillMd"));
        if (content != null && !content.isBlank()) return content;
        // Nested under "skill" key
        Object skillObj = bundle.get("skill");
        if (skillObj instanceof Map) {
            Map<?, ?> skill = (Map<?, ?>) skillObj;
            content = stringOrNull(skill.get("content"));
            if (content != null && !content.isBlank()) return content;
            content = stringOrNull(skill.get("skill_md"));
            if (content != null && !content.isBlank()) return content;
        }
        // From files map
        Object filesObj = bundle.get("files");
        if (filesObj instanceof Map) {
            content = stringOrNull(((Map<?, ?>) filesObj).get("SKILL.md"));
        }
        return content;
    }

    private static String stringOrNull(Object obj) {
        return obj instanceof String ? (String) obj : null;
    }

    // ==================== Builtin Skills ====================

    /** Cached path to extracted builtin skills directory. */
    private volatile Path extractedBuiltinSkillsDir;

    /**
     * Extract builtin skills from classpath resources to a local directory.
     * The Java server bundles its own copy of builtin skills under
     * classpath:builtin-skills/ so it does not depend on the Python source tree.
     */
    private void extractBuiltinSkillsFromClasspath() {
        try {
            // Destination: {data-dir}/builtin_skills/
            Path destDir = dataDir.getDataDir().resolve("builtin_skills");
            Files.createDirectories(destDir);

            // Read index.json to get skill names
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource indexResource = resolver.getResource("classpath:builtin-skills/index.json");
            if (!indexResource.exists()) {
                log.warn("Builtin skills index not found in classpath");
                return;
            }

            List<String> skillNames;
            try (var is = indexResource.getInputStream()) {
                skillNames = objectMapper.readValue(is,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
            }

            // Extract each skill's files from classpath
            for (String skillName : skillNames) {
                Resource[] resources = resolver.getResources(
                        "classpath:builtin-skills/" + skillName + "/**");
                for (Resource resource : resources) {
                    if (!resource.isReadable()) continue;
                    String uri = resource.getURI().toString();
                    // Extract the relative path after "builtin-skills/"
                    int idx = uri.indexOf("builtin-skills/" + skillName + "/");
                    if (idx < 0) continue;
                    String relPath = uri.substring(idx + ("builtin-skills/" + skillName + "/").length());
                    if (relPath.isEmpty()) continue;

                    Path targetFile = destDir.resolve(skillName).resolve(relPath);
                    // Only overwrite if resource is newer (based on content length change)
                    // For simplicity, always overwrite on startup to keep in sync
                    Files.createDirectories(targetFile.getParent());
                    try (var is = resource.getInputStream()) {
                        Files.copy(is, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }

            extractedBuiltinSkillsDir = destDir;
            log.info("Extracted {} builtin skills from classpath to {}", skillNames.size(), destDir);

        } catch (IOException e) {
            log.warn("Failed to extract builtin skills from classpath: {}", e.getMessage());
        }
    }

    /**
     * Get the builtin skills directory.
     * Uses the extracted classpath resources directory.
     */
    private Path getBuiltinSkillsDir() {
        // Primary: extracted from classpath resources
        if (extractedBuiltinSkillsDir != null && Files.exists(extractedBuiltinSkillsDir)) {
            return extractedBuiltinSkillsDir;
        }

        // Fallback: check in data directory
        Path builtinDir = dataDir.getDataDir().resolve("builtin_skills");
        if (Files.exists(builtinDir)) {
            return builtinDir;
        }

        // Last resort fallback
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

        List<String> selectedNames = (request.getSkillNames() != null && !request.getSkillNames().isEmpty()) ?
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

        // Read enabled state from manifest (default false, matching Python)
        boolean enabled = skillNode.has("enabled") && skillNode.get("enabled").asBoolean(false);

        // Read channels from manifest
        List<String> channels = new ArrayList<>();
        if (skillNode.has("channels") && skillNode.get("channels").isArray()) {
            skillNode.get("channels").forEach(ch -> channels.add(ch.asText()));
        }
        if (channels.isEmpty()) {
            channels.add("all");
        }

        // Read config from manifest
        Map<String, Object> config = null;
        if (skillNode.has("config") && skillNode.get("config").isObject()) {
            try {
                config = objectMapper.convertValue(skillNode.get("config"),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.warn("Failed to read skill config: {}", e.getMessage());
            }
        }

        // Read last_updated / updated_at from manifest
        String lastUpdated = skillNode.has("updated_at") ? skillNode.get("updated_at").asText("") : "";

        return SkillInfo.builder()
                .name(skillName)
                .description(skillNode.has("description") ? skillNode.get("description").asText() : "")
                .versionText(skillNode.has("version") ? skillNode.get("version").asText() : "")
                .content(content)
                .source(skillNode.has("source") ? skillNode.get("source").asText() : "customized")
                .enabled(enabled)
                .channels(channels)
                .config(config)
                .lastUpdated(lastUpdated)
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
