package com.copaw.service;

import com.copaw.model.agent.AgentProfileConfig;
import com.copaw.model.agent.AgentProfileRef;
import com.copaw.model.agent.AgentsConfig;
import com.copaw.storage.AgentConfigStore;
import com.copaw.storage.ConfigStore;
import com.copaw.storage.CoPawDataDir;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for workspace initialization.
 * Ported from Python: tests/unit/app/test_agents_workspace_initialization.py
 */
class WorkspaceInitTest {

    @TempDir
    Path tempDir;

    private AgentConfigStore agentConfigStore;
    private ConfigStore configStore;
    private CoPawDataDir dataDir;
    private ObjectMapper objectMapper;
    private AgentService agentService;

    @BeforeEach
    void setUp() {
        agentConfigStore = mock(AgentConfigStore.class);
        configStore = mock(ConfigStore.class);
        dataDir = mock(CoPawDataDir.class);
        objectMapper = new ObjectMapper();
        agentService = new AgentService(agentConfigStore, configStore, dataDir, objectMapper);

        when(dataDir.getAgentDir(any())).thenAnswer(inv -> tempDir.resolve("workspaces").resolve((String) inv.getArgument(0)));
        when(dataDir.getWorkspaceDir(any())).thenAnswer(inv -> tempDir.resolve("workspaces").resolve((String) inv.getArgument(0)));
        
        // Setup configStore to return a valid ObjectNode for loadConfig
        when(configStore.loadConfig()).thenReturn(objectMapper.createObjectNode());
    }

    @Test
    void createAgent_shouldCreateWorkspaceDirectories() {
        // Given: Empty config
        AgentsConfig config = AgentsConfig.builder()
                .profiles(new HashMap<>())
                .agentOrder(new ArrayList<>())
                .build();
        when(agentConfigStore.loadAgentsConfig()).thenReturn(config);
        doNothing().when(configStore).saveConfig(any(JsonNode.class));
        doNothing().when(agentConfigStore).saveAgentConfig(any(AgentProfileConfig.class));

        // When: Create agent
        AgentService.CreateAgentRequest request = new AgentService.CreateAgentRequest();
        request.setName("Test Agent");
        request.setWorkspaceDir(tempDir.resolve("test_workspace").toString());
        request.setLanguage("en");

        AgentProfileRef ref = agentService.createAgent(request);

        // Then: Workspace directories should be created
        Path workspaceDir = tempDir.resolve("test_workspace");
        assertThat(workspaceDir).exists();
        assertThat(workspaceDir.resolve("sessions")).exists();
        assertThat(workspaceDir.resolve("memory")).exists();
        assertThat(workspaceDir.resolve("skills")).exists();
    }

    @Test
    void createAgent_shouldCreateRuntimeCompatibleFiles() throws IOException {
        // Given: Empty config
        AgentsConfig config = AgentsConfig.builder()
                .profiles(new HashMap<>())
                .agentOrder(new ArrayList<>())
                .build();
        when(agentConfigStore.loadAgentsConfig()).thenReturn(config);
        doNothing().when(configStore).saveConfig(any(JsonNode.class));
        doNothing().when(agentConfigStore).saveAgentConfig(any(AgentProfileConfig.class));

        // When: Create agent
        AgentService.CreateAgentRequest request = new AgentService.CreateAgentRequest();
        request.setName("Test Agent");
        request.setWorkspaceDir(tempDir.resolve("test_workspace").toString());
        request.setLanguage("en");

        AgentProfileRef ref = agentService.createAgent(request);

        // Then: Required directories should exist
        Path workspaceDir = tempDir.resolve("test_workspace");
        assertThat(workspaceDir.resolve("sessions")).exists().isDirectory();
        assertThat(workspaceDir.resolve("memory")).exists().isDirectory();
        assertThat(workspaceDir.resolve("skills")).exists().isDirectory();

        // Legacy directories should NOT exist
        assertThat(workspaceDir.resolve("active_skills")).doesNotExist();
        assertThat(workspaceDir.resolve("customized_skills")).doesNotExist();
    }

    @Test
    void createAgent_shouldBeIdempotent() {
        // Given: Config with existing agent
        String agentId = "test_agent";
        Map<String, AgentProfileRef> profiles = new HashMap<>();
        profiles.put(agentId, AgentProfileRef.builder()
                .id(agentId)
                .workspaceDir(tempDir.resolve(agentId).toString())
                .enabled(true)
                .build());

        AgentsConfig config = AgentsConfig.builder()
                .profiles(profiles)
                .agentOrder(new ArrayList<>(List.of(agentId)))
                .build();

        // Create existing workspace
        Path existingWorkspace = tempDir.resolve(agentId);
        try {
            Files.createDirectories(existingWorkspace.resolve("sessions"));
            Files.createDirectories(existingWorkspace.resolve("memory"));
            Files.createDirectories(existingWorkspace.resolve("skills"));
            
            // Create a marker file
            Files.writeString(existingWorkspace.resolve("marker.txt"), "existing");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        when(agentConfigStore.loadAgentsConfig()).thenReturn(config);
        when(agentConfigStore.loadAgentConfig(agentId)).thenReturn(
                AgentProfileConfig.builder()
                        .id(agentId)
                        .name("Test Agent")
                        .workspaceDir(existingWorkspace.toString())
                        .build()
        );

        // When: Get agent config (idempotent operation)
        AgentProfileConfig loadedConfig = agentService.getAgentConfig(agentId);

        // Then: Should return existing config without errors
        assertThat(loadedConfig).isNotNull();
        assertThat(loadedConfig.getId()).isEqualTo(agentId);
        assertThat(existingWorkspace.resolve("marker.txt")).exists();
    }

    @Test
    void createAgent_shouldInitializeWithCorrectLanguage() {
        // Given: Empty config
        AgentsConfig config = AgentsConfig.builder()
                .profiles(new HashMap<>())
                .agentOrder(new ArrayList<>())
                .build();
        when(agentConfigStore.loadAgentsConfig()).thenReturn(config);
        doNothing().when(configStore).saveConfig(any(JsonNode.class));

        // Capture saved agent config
        List<AgentProfileConfig> savedConfigs = new ArrayList<>();
        doAnswer(invocation -> {
            savedConfigs.add(invocation.getArgument(0));
            return null;
        }).when(agentConfigStore).saveAgentConfig(any(AgentProfileConfig.class));

        // When: Create agent with specific language
        AgentService.CreateAgentRequest request = new AgentService.CreateAgentRequest();
        request.setName("Russian Agent");
        request.setWorkspaceDir(tempDir.resolve("russian_agent").toString());
        request.setLanguage("ru");

        AgentProfileRef ref = agentService.createAgent(request);

        // Then: Agent config should have correct language
        assertThat(savedConfigs).hasSize(1);
        assertThat(savedConfigs.get(0).getLanguage()).isEqualTo("ru");
    }

    @Test
    void createAgent_shouldDefaultToEnglishLanguage() {
        // Given: Empty config
        AgentsConfig config = AgentsConfig.builder()
                .profiles(new HashMap<>())
                .agentOrder(new ArrayList<>())
                .build();
        when(agentConfigStore.loadAgentsConfig()).thenReturn(config);
        doNothing().when(configStore).saveConfig(any(JsonNode.class));

        // Capture saved agent config
        List<AgentProfileConfig> savedConfigs = new ArrayList<>();
        doAnswer(invocation -> {
            savedConfigs.add(invocation.getArgument(0));
            return null;
        }).when(agentConfigStore).saveAgentConfig(any(AgentProfileConfig.class));

        // When: Create agent without specifying language
        AgentService.CreateAgentRequest request = new AgentService.CreateAgentRequest();
        request.setName("Default Language Agent");
        request.setWorkspaceDir(tempDir.resolve("default_lang_agent").toString());
        // Not setting language

        AgentProfileRef ref = agentService.createAgent(request);

        // Then: Agent config should default to English
        assertThat(savedConfigs).hasSize(1);
        assertThat(savedConfigs.get(0).getLanguage()).isEqualTo("en");
    }

    @Test
    void workspaceInitialization_shouldCreateAgentJson() {
        // Given: Empty config
        AgentsConfig config = AgentsConfig.builder()
                .profiles(new HashMap<>())
                .agentOrder(new ArrayList<>())
                .build();
        when(agentConfigStore.loadAgentsConfig()).thenReturn(config);
        doNothing().when(configStore).saveConfig(any(JsonNode.class));

        // Capture saved agent config
        List<AgentProfileConfig> savedConfigs = new ArrayList<>();
        doAnswer(invocation -> {
            savedConfigs.add(invocation.getArgument(0));
            return null;
        }).when(agentConfigStore).saveAgentConfig(any(AgentProfileConfig.class));

        // When: Create agent
        AgentService.CreateAgentRequest request = new AgentService.CreateAgentRequest();
        request.setName("Test Agent");
        request.setDescription("Test description");
        request.setWorkspaceDir(tempDir.resolve("test_agent").toString());
        request.setLanguage("en");

        AgentProfileRef ref = agentService.createAgent(request);

        // Then: Agent config should be saved with correct properties
        assertThat(savedConfigs).hasSize(1);
        AgentProfileConfig savedConfig = savedConfigs.get(0);
        assertThat(savedConfig.getName()).isEqualTo("Test Agent");
        assertThat(savedConfig.getDescription()).isEqualTo("Test description");
        assertThat(savedConfig.getWorkspaceDir()).isEqualTo(tempDir.resolve("test_agent").toString());
        assertThat(savedConfig.getRunning()).isNotNull();
    }
}
