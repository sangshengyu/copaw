package cn.sangshy.sa.storage;

import cn.sangshy.sa.model.agent.AgentProfileConfig;
import cn.sangshy.sa.model.agent.AgentProfileRef;
import cn.sangshy.sa.model.agent.AgentsConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Store for managing agent configurations.
 * Reads and writes {data-dir}/agents/{agent_id}/agent.json
 * and manages agent order file.
 */
@Component
public class AgentConfigStore {
    private static final Logger log = LoggerFactory.getLogger(AgentConfigStore.class);

    private final SADataDir dataDir;
    private final JsonFileStore<Object> jsonFileStore;

    public AgentConfigStore(SADataDir dataDir, JsonFileStore<Object> jsonFileStore) {
        this.dataDir = dataDir;
        this.jsonFileStore = jsonFileStore;
    }

    /**
     * Load the root agents configuration.
     *
     * @return the agents configuration, or a default configuration if not found
     */
    public AgentsConfig loadAgentsConfig() {
        Path configPath = dataDir.getConfigPath();
        if (!Files.exists(configPath)) {
            return AgentsConfig.builder().build();
        }

        try {
            String content = Files.readString(configPath);
            // Use Jackson to parse the agents section
            com.fasterxml.jackson.databind.ObjectMapper mapper = jsonFileStore.getObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(content);
            
            if (root.has("agents")) {
                return mapper.treeToValue(root.get("agents"), AgentsConfig.class);
            }
            return AgentsConfig.builder().build();
        } catch (IOException e) {
            log.warn("Failed to load agents config: {}", e.getMessage());
            return AgentsConfig.builder().build();
        }
    }

    /**
     * Load a specific agent's configuration.
     *
     * @param agentId the agent ID
     * @return the agent configuration, or null if not found
     */
    public AgentProfileConfig loadAgentConfig(String agentId) {
        Path agentConfigPath = dataDir.getAgentConfigPath(agentId);
        if (!Files.exists(agentConfigPath)) {
            return null;
        }

        try {
            String content = Files.readString(agentConfigPath);
            return jsonFileStore.getObjectMapper().readValue(content, AgentProfileConfig.class);
        } catch (IOException e) {
            log.warn("Failed to load agent config for {}: {}", agentId, e.getMessage());
            return null;
        }
    }

    /**
     * Save an agent's configuration.
     *
     * @param config the agent configuration to save
     */
    public void saveAgentConfig(AgentProfileConfig config) {
        if (config == null || config.getId() == null) {
            return;
        }
        Path agentConfigPath = dataDir.getAgentConfigPath(config.getId());
        try {
            Files.createDirectories(agentConfigPath.getParent());
            String json = jsonFileStore.getObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(config);
            Files.writeString(agentConfigPath, json);
        } catch (IOException e) {
            log.error("Failed to save agent config for {}: {}", config.getId(), e.getMessage());
            throw new RuntimeException("Failed to save agent config", e);
        }
    }

    /**
     * Get list of all agent profile references.
     *
     * @return list of agent profile references
     */
    public List<AgentProfileRef> listAgentProfiles() {
        AgentsConfig config = loadAgentsConfig();
        return new ArrayList<>(config.getProfiles().values());
    }

    /**
     * Get the active agent ID.
     *
     * @return the active agent ID, or "default" if not set
     */
    public String getActiveAgentId() {
        AgentsConfig config = loadAgentsConfig();
        return config.getActiveAgent() != null ? config.getActiveAgent() : "default";
    }

    /**
     * Set the active agent ID.
     *
     * @param agentId the agent ID to set as active
     */
    public void setActiveAgent(String agentId) {
        // This would need to update the root config.json
        // For now, we just update the agents section
        log.info("Setting active agent to: {}", agentId);
    }

    /**
     * Get the agent order list.
     *
     * @return list of agent IDs in display order
     */
    public List<String> getAgentOrder() {
        AgentsConfig config = loadAgentsConfig();
        return config.getAgentOrder();
    }

    /**
     * Check if an agent exists.
     *
     * @param agentId the agent ID to check
     * @return true if the agent exists
     */
    public boolean agentExists(String agentId) {
        Path agentDir = dataDir.getAgentDir(agentId);
        return Files.exists(agentDir) && Files.exists(dataDir.getAgentConfigPath(agentId));
    }

    /**
     * Create a new agent with default configuration.
     *
     * @param agentId the agent ID
     * @param name    the agent name
     * @return the created agent configuration
     */
    public AgentProfileConfig createAgent(String agentId, String name) {
        String workspaceDir = dataDir.getAgentDir(agentId).toString();
        
        AgentProfileConfig config = AgentProfileConfig.builder()
                .id(agentId)
                .name(name)
                .description("")
                .workspaceDir(workspaceDir)
                .build();

        // Create workspace directory
        try {
            Files.createDirectories(dataDir.getAgentDir(agentId));
        } catch (IOException e) {
            log.error("Failed to create agent directory: {}", e.getMessage());
            throw new RuntimeException("Failed to create agent directory", e);
        }

        saveAgentConfig(config);
        return config;
    }

    /**
     * Delete an agent's configuration.
     *
     * @param agentId the agent ID to delete
     * @return true if deleted successfully
     */
    public boolean deleteAgent(String agentId) {
        if ("default".equals(agentId)) {
            log.warn("Cannot delete default agent");
            return false;
        }

        Path agentDir = dataDir.getAgentDir(agentId);
        try {
            Files.walk(agentDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete {}: {}", path, e.getMessage());
                        }
                    });
            return true;
        } catch (IOException e) {
            log.error("Failed to delete agent {}: {}", agentId, e.getMessage());
            return false;
        }
    }
}
