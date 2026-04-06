package com.copaw.service;

import com.copaw.model.config.*;
import com.copaw.storage.CoPawDataDir;
import com.copaw.storage.ConfigStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Service for managing global configuration.
 */
@Service
public class ConfigService {
    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    private final ConfigStore configStore;
    private final ObjectMapper objectMapper;
    private final CoPawDataDir coPawDataDir;
    private final ObjectMapper yamlMapper;

    // Default sensitive paths for file guard
    private static final List<String> DEFAULT_SENSITIVE_PATHS = Arrays.asList(
        "/etc",
        "/usr/local/etc",
        System.getProperty("user.home") + ".ssh",
        System.getProperty("user.home") + ".aws"
    );

    // Default rule files to load
    private static final List<String> DEFAULT_RULE_FILES = Arrays.asList(
        "dangerous_shell_commands.yaml"
    );

    public ConfigService(ConfigStore configStore, ObjectMapper objectMapper, CoPawDataDir coPawDataDir) {
        this.configStore = configStore;
        this.objectMapper = objectMapper;
        this.coPawDataDir = coPawDataDir;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    /**
     * Get agents LLM routing configuration.
     */
    public AgentsLLMRoutingConfig getAgentsLLMRouting() {
        JsonNode config = configStore.loadConfig();
        JsonNode agentsNode = config.get("agents");
        if (agentsNode != null && agentsNode.has("llm_routing")) {
            try {
                return objectMapper.treeToValue(agentsNode.get("llm_routing"), AgentsLLMRoutingConfig.class);
            } catch (Exception e) {
                log.warn("Failed to parse llm_routing config: {}", e.getMessage());
            }
        }
        return AgentsLLMRoutingConfig.builder().build();
    }

    /**
     * Update agents LLM routing configuration.
     */
    public AgentsLLMRoutingConfig updateAgentsLLMRouting(AgentsLLMRoutingConfig routing) {
        JsonNode config = configStore.loadConfig();
        ObjectNode agentsNode;
        if (config.has("agents") && config.get("agents").isObject()) {
            agentsNode = (ObjectNode) config.get("agents");
        } else {
            agentsNode = objectMapper.createObjectNode();
        }
        agentsNode.set("llm_routing", objectMapper.valueToTree(routing));
        ((ObjectNode) config).set("agents", agentsNode);
        configStore.saveConfig(config);
        return routing;
    }

    /**
     * Get user timezone.
     */
    public String getUserTimezone() {
        return configStore.getString("user_timezone", "UTC");
    }

    /**
     * Update user timezone.
     */
    public String updateUserTimezone(String timezone) {
        configStore.setString("user_timezone", timezone);
        return timezone;
    }

    /**
     * Get Tool Guard configuration.
     */
    public ToolGuardConfig getToolGuard() {
        JsonNode config = configStore.loadConfig();
        JsonNode securityNode = config.get("security");
        if (securityNode != null && securityNode.has("tool_guard")) {
            try {
                return objectMapper.treeToValue(securityNode.get("tool_guard"), ToolGuardConfig.class);
            } catch (Exception e) {
                log.warn("Failed to parse tool_guard config: {}", e.getMessage());
            }
        }
        return ToolGuardConfig.builder().build();
    }

    /**
     * Update Tool Guard configuration.
     */
    public ToolGuardConfig updateToolGuard(ToolGuardConfig toolGuard) {
        JsonNode config = configStore.loadConfig();
        ObjectNode securityNode;
        if (config.has("security") && config.get("security").isObject()) {
            securityNode = (ObjectNode) config.get("security");
        } else {
            securityNode = objectMapper.createObjectNode();
        }
        securityNode.set("tool_guard", objectMapper.valueToTree(toolGuard));
        ((ObjectNode) config).set("security", securityNode);
        configStore.saveConfig(config);
        
        // Note: Guard engine rules reload would be handled by the guard engine component
        return toolGuard;
    }

    /**
     * Get built-in Tool Guard rules.
     * Loads rules from bundled YAML files in the classpath.
     */
    public List<ToolGuardRuleConfig> getBuiltinToolGuardRules() {
        List<ToolGuardRuleConfig> allRules = new ArrayList<>();
        
        for (String ruleFile : DEFAULT_RULE_FILES) {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream("guard-rules/" + ruleFile)) {
                if (is == null) {
                    log.warn("Guard rule file not found in classpath: {}", ruleFile);
                    continue;
                }
                List<ToolGuardRuleConfig> rules = yamlMapper.readValue(
                    is,
                    new TypeReference<List<ToolGuardRuleConfig>>() {}
                );
                allRules.addAll(rules);
                log.debug("Loaded {} rules from {}", rules.size(), ruleFile);
            } catch (IOException e) {
                log.warn("Failed to load guard rules from {}: {}", ruleFile, e.getMessage());
            }
        }
        
        return allRules;
    }

    /**
     * Get File Guard configuration.
     */
    public FileGuardResponse getFileGuard() {
        JsonNode config = configStore.loadConfig();
        JsonNode securityNode = config.get("security");
        
        boolean enabled = true;
        List<String> paths = new ArrayList<>();
        
        if (securityNode != null && securityNode.has("file_guard")) {
            try {
                FileGuardConfig fg = objectMapper.treeToValue(securityNode.get("file_guard"), FileGuardConfig.class);
                if (fg.getEnabled() != null) {
                    enabled = fg.getEnabled();
                }
                if (fg.getSensitiveFiles() != null && !fg.getSensitiveFiles().isEmpty()) {
                    paths = fg.getSensitiveFiles();
                } else {
                    paths = new ArrayList<>(DEFAULT_SENSITIVE_PATHS);
                }
            } catch (Exception e) {
                log.warn("Failed to parse file_guard config: {}", e.getMessage());
                paths = new ArrayList<>(DEFAULT_SENSITIVE_PATHS);
            }
        } else {
            paths = new ArrayList<>(DEFAULT_SENSITIVE_PATHS);
        }
        
        return FileGuardResponse.builder()
            .enabled(enabled)
            .paths(paths)
            .build();
    }

    /**
     * Update File Guard configuration.
     */
    public FileGuardResponse updateFileGuard(FileGuardUpdateBody body) {
        JsonNode config = configStore.loadConfig();
        ObjectNode securityNode;
        if (config.has("security") && config.get("security").isObject()) {
            securityNode = (ObjectNode) config.get("security");
        } else {
            securityNode = objectMapper.createObjectNode();
        }
        
        FileGuardConfig fg;
        if (securityNode.has("file_guard")) {
            try {
                fg = objectMapper.treeToValue(securityNode.get("file_guard"), FileGuardConfig.class);
            } catch (Exception e) {
                fg = FileGuardConfig.builder().build();
            }
        } else {
            fg = FileGuardConfig.builder().build();
        }
        
        if (body.getEnabled() != null) {
            fg.setEnabled(body.getEnabled());
        }
        if (body.getPaths() != null) {
            fg.setSensitiveFiles(body.getPaths());
        }
        
        securityNode.set("file_guard", objectMapper.valueToTree(fg));
        ((ObjectNode) config).set("security", securityNode);
        configStore.saveConfig(config);
        
        // Note: Guard engine rules reload would be handled by the guard engine component
        return FileGuardResponse.builder()
            .enabled(fg.getEnabled())
            .paths(fg.getSensitiveFiles() != null ? fg.getSensitiveFiles() : new ArrayList<>())
            .build();
    }

    /**
     * Get Skill Scanner configuration.
     */
    public SkillScannerConfig getSkillScanner() {
        JsonNode config = configStore.loadConfig();
        JsonNode securityNode = config.get("security");
        if (securityNode != null && securityNode.has("skill_scanner")) {
            try {
                return objectMapper.treeToValue(securityNode.get("skill_scanner"), SkillScannerConfig.class);
            } catch (Exception e) {
                log.warn("Failed to parse skill_scanner config: {}", e.getMessage());
            }
        }
        return SkillScannerConfig.builder().build();
    }

    /**
     * Update Skill Scanner configuration.
     */
    public SkillScannerConfig updateSkillScanner(SkillScannerConfig skillScanner) {
        JsonNode config = configStore.loadConfig();
        ObjectNode securityNode;
        if (config.has("security") && config.get("security").isObject()) {
            securityNode = (ObjectNode) config.get("security");
        } else {
            securityNode = objectMapper.createObjectNode();
        }
        securityNode.set("skill_scanner", objectMapper.valueToTree(skillScanner));
        ((ObjectNode) config).set("security", securityNode);
        configStore.saveConfig(config);
        return skillScanner;
    }

    /**
     * Get blocked skills history.
     * Loads blocked skill records from the blocked history JSON file.
     */
    public List<Map<String, Object>> getBlockedHistory() {
        Path historyPath = coPawDataDir.getBlockedHistoryPath();
        if (!Files.exists(historyPath)) {
            return new ArrayList<>();
        }
        try {
            String content = Files.readString(historyPath);
            return objectMapper.readValue(content, new TypeReference<List<Map<String, Object>>>() {});
        } catch (IOException e) {
            log.warn("Failed to load blocked history: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Clear all blocked skills history.
     * Deletes the blocked history file if it exists.
     */
    public boolean clearBlockedHistory() {
        Path historyPath = coPawDataDir.getBlockedHistoryPath();
        try {
            if (Files.exists(historyPath)) {
                Files.delete(historyPath);
                log.info("Cleared blocked history file");
            }
            return true;
        } catch (IOException e) {
            log.warn("Failed to clear blocked history: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Remove a single blocked history entry by index.
     */
    public boolean removeBlockedEntry(int index) {
        Path historyPath = coPawDataDir.getBlockedHistoryPath();
        if (!Files.exists(historyPath)) {
            return false;
        }
        try {
            String content = Files.readString(historyPath);
            List<Map<String, Object>> history = objectMapper.readValue(
                content,
                new TypeReference<List<Map<String, Object>>>() {}
            );
            if (index < 0 || index >= history.size()) {
                return false;
            }
            history.remove(index);
            Files.writeString(historyPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(history));
            log.info("Removed blocked history entry at index {}", index);
            return true;
        } catch (IOException e) {
            log.warn("Failed to remove blocked entry: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Add a skill to the whitelist.
     */
    public Map<String, Object> addToWhitelist(String skillName, String contentHash) {
        SkillScannerConfig config = getSkillScanner();
        
        // Check if already whitelisted
        for (SkillScannerWhitelistEntry entry : config.getWhitelist()) {
            if (entry.getSkillName().equals(skillName)) {
                throw new IllegalArgumentException("Skill '" + skillName + "' is already whitelisted");
            }
        }
        
        SkillScannerWhitelistEntry newEntry = SkillScannerWhitelistEntry.builder()
            .skillName(skillName)
            .contentHash(contentHash)
            .addedAt(Instant.now().toString())
            .build();
        
        config.getWhitelist().add(newEntry);
        updateSkillScanner(config);
        
        Map<String, Object> result = new HashMap<>();
        result.put("whitelisted", true);
        result.put("skill_name", skillName);
        return result;
    }

    /**
     * Remove a skill from the whitelist.
     */
    public Map<String, Object> removeFromWhitelist(String skillName) {
        SkillScannerConfig config = getSkillScanner();
        int originalSize = config.getWhitelist().size();
        
        config.getWhitelist().removeIf(entry -> entry.getSkillName().equals(skillName));
        
        if (config.getWhitelist().size() == originalSize) {
            throw new IllegalArgumentException("Skill '" + skillName + "' not found in whitelist");
        }
        
        updateSkillScanner(config);
        
        Map<String, Object> result = new HashMap<>();
        result.put("removed", true);
        result.put("skill_name", skillName);
        return result;
    }
}
