package cn.sangshy.sa.storage;

import cn.sangshy.sa.model.agent.AgentsConfig;
import cn.sangshy.sa.model.config.AgentsLLMRoutingConfig;
import cn.sangshy.sa.model.config.AgentsRunningConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Store for managing global configuration.
 * Reads and writes config.json in the data directory.
 */
@Component
public class ConfigStore {
    private static final Logger log = LoggerFactory.getLogger(ConfigStore.class);

    private final SADataDir dataDir;
    private final ObjectMapper objectMapper;

    public ConfigStore(SADataDir dataDir, JsonFileStore<Object> jsonFileStore) {
        this.dataDir = dataDir;
        this.objectMapper = jsonFileStore.getObjectMapper();
    }

    /**
     * Load the entire configuration as a JsonNode.
     *
     * @return the configuration as JsonNode
     */
    public JsonNode loadConfig() {
        Path configPath = dataDir.getConfigPath();
        if (!Files.exists(configPath)) {
            return objectMapper.createObjectNode();
        }

        try {
            String content = Files.readString(configPath);
            return objectMapper.readTree(content);
        } catch (IOException e) {
            log.warn("Failed to load config: {}", e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    /**
     * Save the configuration.
     *
     * @param config the configuration to save
     */
    public void saveConfig(JsonNode config) {
        Path configPath = dataDir.getConfigPath();
        try {
            Files.createDirectories(configPath.getParent());
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
            Files.writeString(configPath, json);
        } catch (IOException e) {
            log.error("Failed to save config: {}", e.getMessage());
            throw new RuntimeException("Failed to save config", e);
        }
    }

    /**
     * Get a specific configuration section.
     *
     * @param section the section name
     * @param clazz   the target class
     * @param <T>     the type
     * @return the configuration section, or null if not found
     */
    public <T> T getSection(String section, Class<T> clazz) {
        JsonNode config = loadConfig();
        JsonNode sectionNode = config.get(section);
        if (sectionNode == null || sectionNode.isNull()) {
            return null;
        }

        try {
            return objectMapper.treeToValue(sectionNode, clazz);
        } catch (IOException e) {
            log.warn("Failed to parse config section {}: {}", section, e.getMessage());
            return null;
        }
    }

    /**
     * Set a specific configuration section.
     *
     * @param section the section name
     * @param value   the value to set
     */
    public void setSection(String section, Object value) {
        JsonNode config = loadConfig();
        if (config.isObject()) {
            ((ObjectNode) config).set(section, objectMapper.valueToTree(value));
            saveConfig(config);
        }
    }

    /**
     * Get a string configuration value.
     *
     * @param key          the key
     * @param defaultValue the default value
     * @return the value, or default if not found
     */
    public String getString(String key, String defaultValue) {
        JsonNode config = loadConfig();
        JsonNode node = config.get(key);
        return node != null && node.isTextual() ? node.asText() : defaultValue;
    }

    /**
     * Set a string configuration value.
     *
     * @param key   the key
     * @param value the value
     */
    public void setString(String key, String value) {
        JsonNode config = loadConfig();
        if (config.isObject()) {
            ((ObjectNode) config).put(key, value);
            saveConfig(config);
        }
    }

    /**
     * Get a boolean configuration value.
     *
     * @param key          the key
     * @param defaultValue the default value
     * @return the value, or default if not found
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        JsonNode config = loadConfig();
        JsonNode node = config.get(key);
        return node != null && node.isBoolean() ? node.asBoolean() : defaultValue;
    }

    /**
     * Set a boolean configuration value.
     *
     * @param key   the key
     * @param value the value
     */
    public void setBoolean(String key, boolean value) {
        JsonNode config = loadConfig();
        if (config.isObject()) {
            ((ObjectNode) config).put(key, value);
            saveConfig(config);
        }
    }

    /**
     * Initialize config with default values if not exists.
     */
    public void initializeIfNeeded() {
        Path configPath = dataDir.getConfigPath();
        if (!Files.exists(configPath)) {
            ObjectNode config = objectMapper.createObjectNode();
            // Set default values
            config.put("show_tool_details", true);
            config.set("agents", objectMapper.valueToTree(
                    AgentsConfig.builder().build()));
            saveConfig(config);
        }
    }
}
