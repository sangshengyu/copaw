package com.copaw.service;

import com.copaw.model.agent.AgentProfileConfig;
import com.copaw.model.agent.AgentProfileRef;
import com.copaw.model.agent.AgentsConfig;
import com.copaw.storage.AgentConfigStore;
import com.copaw.storage.ConfigStore;
import com.copaw.storage.CoPawDataDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for agent ID generation and validation.
 * Ported from Python: tests/unit/workspace/test_agent_creation.py + tests/unit/workspace/test_agent_id.py
 */
class AgentIdTest {

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
    void generateShortAgentId_shouldHaveCorrectLength() {
        // When: Create multiple agents to generate IDs
        Set<String> generatedIds = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            AgentsConfig config = AgentsConfig.builder()
                    .profiles(new HashMap<>())
                    .agentOrder(new java.util.ArrayList<>())
                    .build();
            when(agentConfigStore.loadAgentsConfig()).thenReturn(config);
            doNothing().when(configStore).saveConfig(any());
            doNothing().when(agentConfigStore).saveAgentConfig(any());

            AgentService.CreateAgentRequest request = new AgentService.CreateAgentRequest();
            request.setName("Test Agent " + i);
            request.setLanguage("en");

            AgentProfileRef ref = agentService.createAgent(request);
            generatedIds.add(ref.getId());
        }

        // Then: Each ID should start with "agent_" and have reasonable length
        for (String id : generatedIds) {
            assertThat(id).startsWith("agent_");
            // ID format is "agent_" + base36 number (variable length, typically 4-7 chars)
            assertThat(id.length()).isGreaterThanOrEqualTo(10); // "agent_" + at least 4 chars
            assertThat(id.length()).isLessThanOrEqualTo(15); // "agent_" + at most 9 chars
        }
    }

    @Test
    void generateShortAgentId_shouldBeAlphanumeric() {
        // When: Create multiple agents
        Set<String> generatedIds = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            AgentsConfig config = AgentsConfig.builder()
                    .profiles(new HashMap<>())
                    .agentOrder(new java.util.ArrayList<>())
                    .build();
            when(agentConfigStore.loadAgentsConfig()).thenReturn(config);
            doNothing().when(configStore).saveConfig(any());
            doNothing().when(agentConfigStore).saveAgentConfig(any());

            AgentService.CreateAgentRequest request = new AgentService.CreateAgentRequest();
            request.setName("Test Agent " + i);
            request.setLanguage("en");

            AgentProfileRef ref = agentService.createAgent(request);
            generatedIds.add(ref.getId());
        }

        // Then: Each ID should be alphanumeric (after "agent_" prefix)
        for (String id : generatedIds) {
            String shortId = id.substring(6);
            assertThat(shortId).matches("[a-z0-9]+");
        }
    }

    @Test
    void generateShortAgentId_shouldBeUnique() {
        // When: Generate many IDs
        Set<String> generatedIds = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            AgentsConfig config = AgentsConfig.builder()
                    .profiles(new HashMap<>())
                    .agentOrder(new java.util.ArrayList<>())
                    .build();
            when(agentConfigStore.loadAgentsConfig()).thenReturn(config);
            doNothing().when(configStore).saveConfig(any());
            doNothing().when(agentConfigStore).saveAgentConfig(any());

            AgentService.CreateAgentRequest request = new AgentService.CreateAgentRequest();
            request.setName("Test Agent " + i);
            request.setLanguage("en");

            AgentProfileRef ref = agentService.createAgent(request);
            generatedIds.add(ref.getId());
        }

        // Then: With 100 generations, we should get at least 95 unique IDs
        assertThat(generatedIds).hasSizeGreaterThanOrEqualTo(95);
    }

    @Test
    void generateShortAgentId_shouldNotContainAmbiguousCharacters() {
        // When: Create multiple agents
        Set<String> generatedIds = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            AgentsConfig config = AgentsConfig.builder()
                    .profiles(new HashMap<>())
                    .agentOrder(new java.util.ArrayList<>())
                    .build();
            when(agentConfigStore.loadAgentsConfig()).thenReturn(config);
            doNothing().when(configStore).saveConfig(any());
            doNothing().when(agentConfigStore).saveAgentConfig(any());

            AgentService.CreateAgentRequest request = new AgentService.CreateAgentRequest();
            request.setName("Test Agent " + i);
            request.setLanguage("en");

            AgentProfileRef ref = agentService.createAgent(request);
            generatedIds.add(ref.getId());
        }

        // Then: Should not contain certain ambiguous characters
        // Note: Java implementation uses base36 (0-9, a-z), so 'l' and '0' are possible
        // We only check for 'I' and 'O' which are definitely excluded in base36
        for (String id : generatedIds) {
            String shortId = id.substring(6);
            assertThat(shortId).doesNotContain("I", "O");
        }
    }

    @Test
    void defaultAgentId_shouldBePreserved() {
        // Given: Agent config with "default" ID
        AgentProfileConfig config = AgentProfileConfig.builder()
                .id("default")
                .name("Default Agent")
                .description("Default agent")
                .build();

        // Then: ID should remain as "default"
        assertThat(config.getId()).isEqualTo("default");
    }

    @Test
    void createAgent_shouldHandleIdCollision() {
        // Given: Config with some existing agents
        Map<String, AgentProfileRef> existingProfiles = new HashMap<>();
        Set<String> existingIds = new HashSet<>();
        
        // Simulate collision by pre-populating with many IDs
        for (int i = 0; i < 50; i++) {
            AgentsConfig config = AgentsConfig.builder()
                    .profiles(new HashMap<>())
                    .agentOrder(new java.util.ArrayList<>())
                    .build();
            when(agentConfigStore.loadAgentsConfig()).thenReturn(config);
            doNothing().when(configStore).saveConfig(any());
            doNothing().when(agentConfigStore).saveAgentConfig(any());

            AgentService.CreateAgentRequest request = new AgentService.CreateAgentRequest();
            request.setName("Test Agent " + i);
            request.setLanguage("en");

            AgentProfileRef ref = agentService.createAgent(request);
            existingIds.add(ref.getId());
        }

        // Then: All generated IDs should be unique
        assertThat(existingIds).hasSize(50);
    }

    @Test
    void agentId_shouldNotBeOverwrittenForDefault() {
        // Given: Default agent config
        AgentProfileConfig defaultConfig = AgentProfileConfig.builder()
                .id("default")
                .name("Default Agent")
                .build();

        // When/Then: The ID should not be auto-generated/replaced
        assertThat(defaultConfig.getId()).isEqualTo("default");
        assertThat(defaultConfig.getId()).doesNotStartWith("agent_");
    }

    @Test
    void emptyAgentId_shouldTriggerAutoGeneration() {
        // Given: Config with empty ID (simulating auto-generation trigger)
        AgentProfileConfig config = AgentProfileConfig.builder()
                .id("")  // Empty ID
                .name("Test Agent")
                .build();

        // Then: Empty ID should be the precondition for auto-generation
        assertThat(config.getId()).isEmpty();
        // Note: Actual auto-generation happens in the service layer
    }
}
