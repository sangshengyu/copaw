package com.copaw.controller;

import com.copaw.storage.CoPawDataDir;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class SettingsControllerTest {

    @TempDir
    Path tempDir;

    @Mock
    private CoPawDataDir dataDir;

    private ObjectMapper objectMapper;
    private SettingsController settingsController;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        lenient().when(dataDir.getDataDir()).thenReturn(tempDir);
        settingsController = new SettingsController(dataDir, objectMapper);
    }

    @Test
    void getLanguageShouldReturnDefaultWhenNoSettingsFileExists() {
        Map<String, String> result = settingsController.getLanguage();
        assertThat(result).containsEntry("language", "en");
    }

    @Test
    void getLanguageShouldReturnPersistedValue() throws Exception {
        Path settingsFile = tempDir.resolve("settings.json");
        objectMapper.writeValue(settingsFile.toFile(), Map.of("language", "zh"));

        Map<String, String> result = settingsController.getLanguage();
        assertThat(result).containsEntry("language", "zh");
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "zh", "ja", "ru"})
    void putLanguageValidShouldAcceptAndPersist(String lang) throws Exception {
        Map<String, String> request = Map.of("language", lang);
        Map<String, String> result = settingsController.updateLanguage(request);

        assertThat(result).containsEntry("language", lang);
        
        Path settingsFile = tempDir.resolve("settings.json");
        assertThat(settingsFile).exists();
        JsonNode saved = objectMapper.readTree(settingsFile.toFile());
        assertThat(saved.get("language").asText()).isEqualTo(lang);
    }

    @Test
    void putLanguageInvalidShouldRejectWith400() {
        Map<String, String> request = Map.of("language", "xx");

        assertThatThrownBy(() -> settingsController.updateLanguage(request))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    void putLanguageShouldPreserveOtherSettings() throws Exception {
        Path settingsFile = tempDir.resolve("settings.json");
        objectMapper.writeValue(settingsFile.toFile(), 
                Map.of("theme", "dark", "language", "en"));

        Map<String, String> request = Map.of("language", "zh");
        settingsController.updateLanguage(request);

        JsonNode saved = objectMapper.readTree(settingsFile.toFile());
        assertThat(saved.get("language").asText()).isEqualTo("zh");
        assertThat(saved.get("theme").asText()).isEqualTo("dark");
    }
}
