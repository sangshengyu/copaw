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
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for AgentService - agent ordering and management.
 * Ported from Python: tests/unit/app/test_agents_ordering.py
 */
class AgentServiceTest {

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

    private AgentsConfig buildConfig(List<String> profileIds, List<String> agentOrder) {
        Map<String, AgentProfileRef> profiles = new HashMap<>();
        for (String agentId : profileIds) {
            profiles.put(agentId, AgentProfileRef.builder()
                    .id(agentId)
                    .workspaceDir(tempDir.resolve(agentId).toString())
                    .enabled(true)
                    .build());
        }
        return AgentsConfig.builder()
                .profiles(profiles)
                .agentOrder(agentOrder != null ? new ArrayList<>(agentOrder) : new ArrayList<>())
                .build();
    }

    private AgentProfileConfig buildAgentConfig(String agentId) {
        return AgentProfileConfig.builder()
                .id(agentId)
                .name(agentId.toUpperCase())
                .description(agentId + " description")
                .workspaceDir(tempDir.resolve(agentId).toString())
                .build();
    }

    @Test
    void listAgents_shouldUsePersistedOrder() {
        // Given: Config with profiles [beta, default, alpha] but order [default, alpha, beta]
        AgentsConfig config = buildConfig(
                List.of("beta", "default", "alpha"),
                List.of("default", "alpha", "beta")
        );
        when(agentConfigStore.loadAgentsConfig()).thenReturn(config);
        when(agentConfigStore.loadAgentConfig(any())).thenAnswer(inv -> 
                buildAgentConfig((String) inv.getArgument(0)));

        // When: List agents
        List<AgentService.AgentSummary> agents = agentService.listAgents();

        // Then: Should follow stored order
        assertThat(agents).hasSize(3);
        assertThat(agents).extracting(AgentService.AgentSummary::getId)
                .containsExactly("default", "alpha", "beta");
    }

    @Test
    void listAgents_shouldAppendMissingIds() {
        // Given: Old config with incomplete order
        AgentsConfig config = buildConfig(
                List.of("beta", "default", "alpha"),
                List.of("default")  // Only default in order, missing beta and alpha
        );
        when(agentConfigStore.loadAgentsConfig()).thenReturn(config);
        when(agentConfigStore.loadAgentConfig(any())).thenAnswer(inv -> 
                buildAgentConfig((String) inv.getArgument(0)));

        // When: List agents
        List<AgentService.AgentSummary> agents = agentService.listAgents();

        // Then: Should return all agents with missing ones appended
        assertThat(agents).hasSize(3);
        // First should be "default" (from order), remaining can be in any order (from HashMap)
        assertThat(agents.get(0).getId()).isEqualTo("default");
        // Check all expected IDs are present (order of appended items not guaranteed due to HashMap)
        assertThat(agents).extracting(AgentService.AgentSummary::getId)
                .containsExactlyInAnyOrder("default", "beta", "alpha");
    }

    @Test
    void reorderAgents_shouldRejectIncompletePayload() {
        // Given: Config with 3 agents
        AgentsConfig config = buildConfig(
                List.of("default", "alpha", "beta"),
                List.of("default", "alpha", "beta")
        );
        when(agentConfigStore.loadAgentsConfig()).thenReturn(config);

        // When/Then: Reorder with incomplete list should throw exception
        assertThatThrownBy(() -> agentService.reorderAgents(List.of("alpha", "default")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly once");
    }

    @Test
    void reorderAgents_shouldPersistValidOrder() {
        // Given: Config with 3 agents
        AgentsConfig config = buildConfig(
                List.of("default", "alpha", "beta"),
                List.of("default", "alpha", "beta")
        );
        when(agentConfigStore.loadAgentsConfig()).thenReturn(config);
        doNothing().when(configStore).saveConfig(any(JsonNode.class));

        // When: Reorder agents
        Map<String, Object> result = agentService.reorderAgents(List.of("beta", "default", "alpha"));

        // Then: Should save new order
        assertThat(result).containsEntry("success", true);
        assertThat(config.getAgentOrder()).containsExactly("beta", "default", "alpha");

        // Verify save was called
        ArgumentCaptor<JsonNode> configCaptor = ArgumentCaptor.forClass(JsonNode.class);
        verify(configStore).saveConfig(configCaptor.capture());
    }

    @Test
    void createAgent_shouldAppendNewIdToOrder() {
        // Given: Config with existing agents
        AgentsConfig config = buildConfig(
                List.of("default", "alpha"),
                List.of("alpha", "default")
        );
        when(agentConfigStore.loadAgentsConfig()).thenReturn(config);
        doNothing().when(configStore).saveConfig(any(JsonNode.class));
        doNothing().when(agentConfigStore).saveAgentConfig(any(AgentProfileConfig.class));

        // When: Create new agent
        AgentService.CreateAgentRequest request = new AgentService.CreateAgentRequest();
        request.setName("Beta");
        request.setWorkspaceDir(tempDir.resolve("beta").toString());
        request.setLanguage("en");

        AgentProfileRef ref = agentService.createAgent(request);

        // Then: New agent should be appended to order
        assertThat(ref).isNotNull();
        assertThat(config.getAgentOrder()).containsExactly("alpha", "default", ref.getId());
    }

    @Test
    void deleteAgent_shouldRemoveIdFromOrder() {
        // Given: Config with 3 agents
        AgentsConfig config = buildConfig(
                List.of("default", "alpha", "beta"),
                List.of("alpha", "default", "beta")
        );
        when(agentConfigStore.loadAgentsConfig()).thenReturn(config);
        doNothing().when(configStore).saveConfig(any(JsonNode.class));
        when(agentConfigStore.deleteAgent("beta")).thenReturn(true);

        // When: Delete beta agent
        boolean result = agentService.deleteAgent("beta");

        // Then: Beta should be removed from order
        assertThat(result).isTrue();
        assertThat(config.getAgentOrder()).containsExactly("alpha", "default");
        assertThat(config.getProfiles()).doesNotContainKey("beta");
    }

    @Test
    void deleteAgent_shouldNotAllowDeletingDefaultAgent() {
        // When/Then: Deleting default agent should throw exception
        assertThatThrownBy(() -> agentService.deleteAgent("default"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot delete the default agent");
    }
}
