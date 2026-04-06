package cn.sangshy.sa.controller;

import cn.sangshy.sa.storage.SADataDir;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Controller for managing global UI settings (language, theme, etc.).
 * Persisted in {data-dir}/settings.json, independent of per-agent configuration.
 */
@RestController
@RequestMapping("/settings")
public class SettingsController {
    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);
    private static final Set<String> VALID_LANGUAGES = Set.of("en", "zh", "ja", "ru");

    private final SADataDir dataDir;
    private final ObjectMapper objectMapper;

    public SettingsController(SADataDir dataDir, ObjectMapper objectMapper) {
        this.dataDir = dataDir;
        this.objectMapper = objectMapper;
    }

    /**
     * Get UI language setting.
     * GET /settings/language
     */
    @GetMapping("/language")
    public Map<String, String> getLanguage() {
        JsonNode settings = loadSettings();
        String language = settings.has("language")
                ? settings.get("language").asText()
                : "en";
        return Map.of("language", language);
    }

    /**
     * Update UI language setting.
     * PUT /settings/language
     */
    @PutMapping("/language")
    public Map<String, String> updateLanguage(@RequestBody Map<String, String> request) {
        String language = request.get("language");
        if (language == null || language.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "language is required");
        }

        language = language.strip().toLowerCase();
        if (!VALID_LANGUAGES.contains(language)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid language '" + language + "'. Must be one of: " + VALID_LANGUAGES);
        }

        try {
            JsonNode settings = loadSettings();
            ((ObjectNode) settings).put("language", language);
            saveSettings(settings);
        } catch (IOException e) {
            log.error("Failed to save settings", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to save settings");
        }

        return Map.of("language", language);
    }

    // ==================== Helper Methods ====================

    private Path getSettingsFile() {
        return dataDir.getDataDir().resolve("settings.json");
    }

    private JsonNode loadSettings() {
        Path settingsFile = getSettingsFile();
        if (!Files.exists(settingsFile)) {
            return objectMapper.createObjectNode();
        }

        try {
            return objectMapper.readTree(Files.readString(settingsFile));
        } catch (IOException e) {
            log.warn("Failed to load settings: {}", e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    private void saveSettings(JsonNode settings) throws IOException {
        Path settingsFile = getSettingsFile();
        Files.createDirectories(settingsFile.getParent());
        Files.writeString(settingsFile,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(settings));
    }
}
