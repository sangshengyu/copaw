package com.copaw.service;

import com.copaw.model.agent.AgentProfileConfig;
import com.copaw.model.agent.AgentProfileRef;
import com.copaw.model.agent.AgentsConfig;
import com.copaw.model.common.MdFileInfo;
import com.copaw.model.config.AgentsRunningConfig;
import com.copaw.storage.AgentConfigStore;
import com.copaw.storage.ConfigStore;
import com.copaw.storage.CoPawDataDir;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for managing agents.
 */
@Service
public class AgentService {
    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final AgentConfigStore agentConfigStore;
    private final ConfigStore configStore;
    private final CoPawDataDir dataDir;
    private final ObjectMapper objectMapper;

    public AgentService(
            AgentConfigStore agentConfigStore,
            ConfigStore configStore,
            CoPawDataDir dataDir,
            ObjectMapper objectMapper) {
        this.agentConfigStore = agentConfigStore;
        this.configStore = configStore;
        this.dataDir = dataDir;
        this.objectMapper = objectMapper;
    }

    /**
     * Get the active agent ID.
     *
     * @return the active agent ID
     */
    public String getActiveAgentId() {
        AgentsConfig config = agentConfigStore.loadAgentsConfig();
        String activeAgent = config.getActiveAgent();
        return activeAgent != null ? activeAgent : "default";
    }

    /**
     * List all agents with their summary information.
     *
     * @return list of agent summaries
     */
    public List<AgentSummary> listAgents() {
        AgentsConfig config = agentConfigStore.loadAgentsConfig();
        List<String> orderedIds = normalizeAgentOrder(config);
        List<AgentSummary> agents = new ArrayList<>();

        for (String agentId : orderedIds) {
            AgentProfileRef ref = config.getProfiles().get(agentId);
            if (ref == null) continue;

            AgentProfileConfig agentConfig = agentConfigStore.loadAgentConfig(agentId);
            String name = agentConfig != null && agentConfig.getName() != null
                    ? agentConfig.getName()
                    : capitalize(agentId);
            String description = agentConfig != null && agentConfig.getDescription() != null
                    ? agentConfig.getDescription()
                    : "";

            // Read profile description from PROFILE.md if exists
            String profileDesc = readProfileDescription(ref.getWorkspaceDir());
            if (!profileDesc.isEmpty()) {
                if (!description.isEmpty()) {
                    description = description + " | " + profileDesc;
                } else {
                    description = profileDesc;
                }
            }

            agents.add(new AgentSummary(
                    agentId,
                    name,
                    description,
                    ref.getWorkspaceDir(),
                    ref.getEnabled() != null ? ref.getEnabled() : true
            ));
        }

        return agents;
    }

    /**
     * Get an agent's configuration.
     * If the agent doesn't exist, returns a default configuration.
     *
     * @param agentId the agent ID
     * @return the agent configuration (never null)
     */
    public AgentProfileConfig getAgentConfig(String agentId) {
        AgentProfileConfig config = agentConfigStore.loadAgentConfig(agentId);
        if (config == null) {
            // Return a default configuration if agent doesn't exist
            log.info("Creating default config for agent: {}", agentId);
            config = AgentProfileConfig.builder()
                    .id(agentId)
                    .name(capitalize(agentId))
                    .description("")
                    .workspaceDir(dataDir.getAgentDir(agentId).toString())
                    .language("zh")
                    .running(new AgentsRunningConfig())
                    .build();
        }
        return config;
    }

    /**
     * Create a new agent.
     *
     * @param request the creation request
     * @return the created agent reference
     */
    public AgentProfileRef createAgent(CreateAgentRequest request) {
        AgentsConfig config = agentConfigStore.loadAgentsConfig();

        // Generate unique ID
        String newId = generateUniqueAgentId(config);
        if (newId == null) {
            throw new RuntimeException("Failed to generate unique agent ID after 10 attempts");
        }

        // Determine workspace directory
        String workspaceDir = request.getWorkspaceDir();
        if (workspaceDir == null || workspaceDir.isBlank()) {
            workspaceDir = dataDir.getWorkspaceDir(newId).toString();
        }
        Path workspacePath = Path.of(workspaceDir);

        // Create workspace directory
        try {
            Files.createDirectories(workspacePath);
            Files.createDirectories(workspacePath.resolve("sessions"));
            Files.createDirectories(workspacePath.resolve("memory"));
            Files.createDirectories(workspacePath.resolve("skills"));
        } catch (IOException e) {
            throw new RuntimeException("Failed to create workspace directory", e);
        }

        // Create agent configuration
        AgentProfileConfig agentConfig = AgentProfileConfig.builder()
                .id(newId)
                .name(request.getName())
                .description(request.getDescription() != null ? request.getDescription() : "")
                .workspaceDir(workspaceDir)
                .language(request.getLanguage() != null ? request.getLanguage() : "en")
                .running(new AgentsRunningConfig())
                .build();

        // Create agent reference
        AgentProfileRef ref = AgentProfileRef.builder()
                .id(newId)
                .workspaceDir(workspaceDir)
                .enabled(true)
                .build();

        // Update config
        config.getProfiles().put(newId, ref);
        config.setAgentOrder(normalizeAgentOrder(config));
        saveAgentsConfig(config);

        // Save agent config
        agentConfigStore.saveAgentConfig(agentConfig);

        log.info("Created new agent: {} (name={})", newId, request.getName());
        return ref;
    }

    /**
     * Update an agent's configuration.
     *
     * @param agentId      the agent ID
     * @param newConfig    the new configuration
     * @return the updated configuration
     */
    public AgentProfileConfig updateAgent(String agentId, AgentProfileConfig newConfig) {
        AgentsConfig config = agentConfigStore.loadAgentsConfig();

        if (!config.getProfiles().containsKey(agentId)) {
            throw new IllegalArgumentException("Agent not found: " + agentId);
        }

        AgentProfileConfig existingConfig = agentConfigStore.loadAgentConfig(agentId);
        if (existingConfig == null) {
            throw new IllegalArgumentException("Agent not found: " + agentId);
        }

        // Update fields (except id)
        if (newConfig.getName() != null) existingConfig.setName(newConfig.getName());
        if (newConfig.getDescription() != null) existingConfig.setDescription(newConfig.getDescription());
        if (newConfig.getWorkspaceDir() != null) existingConfig.setWorkspaceDir(newConfig.getWorkspaceDir());
        if (newConfig.getLanguage() != null) existingConfig.setLanguage(newConfig.getLanguage());
        if (newConfig.getChannels() != null) existingConfig.setChannels(newConfig.getChannels());
        if (newConfig.getMcp() != null) existingConfig.setMcp(newConfig.getMcp());
        if (newConfig.getHeartbeat() != null) existingConfig.setHeartbeat(newConfig.getHeartbeat());
        if (newConfig.getRunning() != null) existingConfig.setRunning(newConfig.getRunning());
        if (newConfig.getLlmRouting() != null) existingConfig.setLlmRouting(newConfig.getLlmRouting());
        if (newConfig.getSystemPromptFiles() != null) existingConfig.setSystemPromptFiles(newConfig.getSystemPromptFiles());
        if (newConfig.getActiveModel() != null) existingConfig.setActiveModel(newConfig.getActiveModel());
        if (newConfig.getTools() != null) existingConfig.setTools(newConfig.getTools());
        if (newConfig.getSecurity() != null) existingConfig.setSecurity(newConfig.getSecurity());

        existingConfig.setId(agentId);
        agentConfigStore.saveAgentConfig(existingConfig);

        return existingConfig;
    }

    /**
     * Delete an agent.
     *
     * @param agentId the agent ID
     * @return true if deleted
     */
    public boolean deleteAgent(String agentId) {
        if ("default".equals(agentId)) {
            throw new IllegalArgumentException("Cannot delete the default agent");
        }

        AgentsConfig config = agentConfigStore.loadAgentsConfig();

        if (!config.getProfiles().containsKey(agentId)) {
            throw new IllegalArgumentException("Agent not found: " + agentId);
        }

        // Remove from config
        config.getProfiles().remove(agentId);
        config.setAgentOrder(normalizeAgentOrder(config));
        saveAgentsConfig(config);

        // Delete workspace
        agentConfigStore.deleteAgent(agentId);

        log.info("Deleted agent: {}", agentId);
        return true;
    }

    /**
     * Toggle agent enabled state.
     *
     * @param agentId the agent ID
     * @param enabled the enabled state
     * @return the updated state
     */
    public Map<String, Object> toggleAgent(String agentId, boolean enabled) {
        if ("default".equals(agentId)) {
            throw new IllegalArgumentException("Cannot disable the default agent");
        }

        AgentsConfig config = agentConfigStore.loadAgentsConfig();

        AgentProfileRef ref = config.getProfiles().get(agentId);
        if (ref == null) {
            throw new IllegalArgumentException("Agent not found: " + agentId);
        }

        ref.setEnabled(enabled);
        saveAgentsConfig(config);

        log.info("Agent {} {}", agentId, enabled ? "enabled" : "disabled");
        return Map.of("success", true, "agent_id", agentId, "enabled", enabled);
    }

    /**
     * Reorder agents.
     *
     * @param agentIds the new order of agent IDs
     * @return the updated order
     */
    public Map<String, Object> reorderAgents(List<String> agentIds) {
        AgentsConfig config = agentConfigStore.loadAgentsConfig();
        Set<String> configuredIds = new HashSet<>(config.getProfiles().keySet());

        // Validate
        if (new HashSet<>(agentIds).size() != agentIds.size()) {
            throw new IllegalArgumentException("Each agent ID must appear exactly once");
        }
        if (!new HashSet<>(agentIds).equals(configuredIds)) {
            throw new IllegalArgumentException("Each agent ID must appear exactly once");
        }

        config.setAgentOrder(new ArrayList<>(agentIds));
        saveAgentsConfig(config);

        return Map.of("success", true, "agent_ids", config.getAgentOrder());
    }

    /**
     * List agent workspace files.
     *
     * @param agentId the agent ID
     * @return list of MD files
     */
    public List<MdFileInfo> listAgentFiles(String agentId) {
        AgentProfileRef ref = getAgentRef(agentId);
        if (ref == null) {
            throw new IllegalArgumentException("Agent not found: " + agentId);
        }
        return listMdFiles(ref.getWorkspaceDir(), "");
    }

    /**
     * Read agent workspace file.
     *
     * @param agentId  the agent ID
     * @param filename the file name
     * @return the file content
     */
    public String readAgentFile(String agentId, String filename) {
        AgentProfileRef ref = getAgentRef(agentId);
        if (ref == null) {
            throw new IllegalArgumentException("Agent not found: " + agentId);
        }

        Path filePath = Path.of(ref.getWorkspaceDir()).resolve(filename + ".md");
        if (!Files.exists(filePath)) {
            filePath = Path.of(ref.getWorkspaceDir()).resolve(filename);
        }
        if (!Files.exists(filePath)) {
            throw new IllegalArgumentException("File not found: " + filename);
        }

        try {
            return Files.readString(filePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file: " + filename, e);
        }
    }

    /**
     * Write agent workspace file.
     *
     * @param agentId  the agent ID
     * @param filename the file name
     * @param content  the file content
     * @return true if written
     */
    public boolean writeAgentFile(String agentId, String filename, String content) {
        AgentProfileRef ref = getAgentRef(agentId);
        if (ref == null) {
            throw new IllegalArgumentException("Agent not found: " + agentId);
        }

        Path filePath = Path.of(ref.getWorkspaceDir()).resolve(filename + ".md");
        if (!filename.endsWith(".md")) {
            filePath = Path.of(ref.getWorkspaceDir()).resolve(filename);
        }

        try {
            Files.writeString(filePath, content);
            return true;
        } catch (IOException e) {
            throw new RuntimeException("Failed to write file: " + filename, e);
        }
    }

    /**
     * List agent memory files.
     *
     * @param agentId the agent ID
     * @return list of memory files
     */
    public List<MdFileInfo> listAgentMemory(String agentId) {
        AgentProfileRef ref = getAgentRef(agentId);
        if (ref == null) {
            throw new IllegalArgumentException("Agent not found: " + agentId);
        }
        return listMdFiles(ref.getWorkspaceDir(), "memory");
    }

    /**
     * Get agent running config.
     *
     * @param agentId the agent ID
     * @return the running config
     */
    public AgentsRunningConfig getRunningConfig(String agentId) {
        AgentProfileConfig config = getAgentConfig(agentId);
        return config.getRunning() != null ? config.getRunning() : new AgentsRunningConfig();
    }

    /**
     * Update agent running config.
     *
     * @param agentId  the agent ID
     * @param newConfig the new config
     * @return the updated config
     */
    public AgentsRunningConfig updateRunningConfig(String agentId, AgentsRunningConfig newConfig) {
        AgentProfileConfig config = getAgentConfig(agentId);
        config.setRunning(newConfig);
        agentConfigStore.saveAgentConfig(config);
        return newConfig;
    }

    /**
     * Get system prompt files.
     *
     * @param agentId the agent ID
     * @return list of system prompt files
     */
    public List<String> getSystemPromptFiles(String agentId) {
        AgentProfileConfig config = getAgentConfig(agentId);
        return config.getSystemPromptFiles() != null
                ? config.getSystemPromptFiles()
                : List.of("AGENTS.md", "SOUL.md", "PROFILE.md");
    }

    /**
     * Update system prompt files.
     *
     * @param agentId the agent ID
     * @param files   the file list
     * @return the updated list
     */
    public List<String> updateSystemPromptFiles(String agentId, List<String> files) {
        AgentProfileConfig config = getAgentConfig(agentId);
        config.setSystemPromptFiles(new ArrayList<>(files));
        agentConfigStore.saveAgentConfig(config);
        return files;
    }

    /**
     * Get the workspace directory for an agent.
     *
     * @param agentId the agent ID
     * @return the workspace directory path
     */
    public String getAgentWorkspaceDir(String agentId) {
        AgentProfileConfig config = getAgentConfig(agentId);
        return config.getWorkspaceDir();
    }

    // Helper methods

    private AgentProfileRef getAgentRef(String agentId) {
        AgentsConfig config = agentConfigStore.loadAgentsConfig();
        return config.getProfiles().get(agentId);
    }

    private List<String> normalizeAgentOrder(AgentsConfig config) {
        Set<String> profileIds = new HashSet<>(config.getProfiles().keySet());
        List<String> orderedIds = new ArrayList<>();

        // Add existing order that still exists
        for (String id : config.getAgentOrder()) {
            if (config.getProfiles().containsKey(id) && !orderedIds.contains(id)) {
                orderedIds.add(id);
            }
        }

        // Add any missing profiles
        for (String id : profileIds) {
            if (!orderedIds.contains(id)) {
                orderedIds.add(id);
            }
        }

        return orderedIds;
    }

    private String generateUniqueAgentId(AgentsConfig config) {
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < 10; i++) {
            String id = "agent_" + Integer.toString(random.nextInt(900000) + 100000, 36);
            if (!config.getProfiles().containsKey(id)) {
                return id;
            }
        }
        return null;
    }

    private void saveAgentsConfig(AgentsConfig agentsConfig) {
        JsonNode config = configStore.loadConfig();
        if (config.isObject()) {
            ((ObjectNode) config).set("agents", objectMapper.valueToTree(agentsConfig));
            configStore.saveConfig(config);
        }
    }

    private String readProfileDescription(String workspaceDir) {
        if (workspaceDir == null) return "";
        Path profilePath = Path.of(workspaceDir).resolve("PROFILE.md");
        if (!Files.exists(profilePath)) return "";

        try {
            String content = Files.readString(profilePath).strip();
            StringBuilder description = new StringBuilder();
            boolean inIdentity = false;

            for (String line : content.split("\n")) {
                String stripped = line.strip();
                if (stripped.startsWith("## 身份") || stripped.startsWith("## Identity")) {
                    inIdentity = true;
                    continue;
                }
                if (inIdentity) {
                    if (stripped.startsWith("##")) break;
                    if (!stripped.isEmpty() && !stripped.startsWith("#")) {
                        if (description.length() > 0) description.append(" ");
                        description.append(stripped);
                    }
                }
            }

            String result = description.toString();
            return result.length() > 200 ? result.substring(0, 200) : result;
        } catch (IOException e) {
            return "";
        }
    }

    private List<MdFileInfo> listMdFiles(String workspaceDir, String subDir) {
        List<MdFileInfo> files = new ArrayList<>();
        if (workspaceDir == null) return files;

        Path dir = subDir.isEmpty()
                ? Path.of(workspaceDir)
                : Path.of(workspaceDir).resolve(subDir);

        if (!Files.exists(dir)) return files;

        try {
            DateTimeFormatter formatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneId.systemDefault());
            Files.walk(dir, 1)
                    .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".md"))
                    .forEach(p -> {
                        try {
                            files.add(MdFileInfo.builder()
                                    .filename(p.getFileName().toString())
                                    .path(subDir.isEmpty() ? p.getFileName().toString() : subDir + "/" + p.getFileName())
                                    .size(Files.size(p))
                                    .modifiedAt(formatter.format(Files.getLastModifiedTime(p).toInstant()))
                                    .build());
                        } catch (IOException e) {
                            log.warn("Failed to get file info: {}", p, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to list files in: {}", dir, e);
        }

        return files;
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    // Inner classes

    public static class AgentSummary {
        private final String id;
        private final String name;
        private final String description;
        private final String workspaceDir;
        private final boolean enabled;

        public AgentSummary(String id, String name, String description, String workspaceDir, boolean enabled) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.workspaceDir = workspaceDir;
            this.enabled = enabled;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public String getWorkspaceDir() { return workspaceDir; }
        public boolean isEnabled() { return enabled; }
    }

    public static class CreateAgentRequest {
        private String name;
        private String description;
        private String workspaceDir;
        private String language;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getWorkspaceDir() { return workspaceDir; }
        public void setWorkspaceDir(String workspaceDir) { this.workspaceDir = workspaceDir; }
        public String getLanguage() { return language; }
        public void setLanguage(String language) { this.language = language; }
    }
}
