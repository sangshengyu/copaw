package com.copaw.agent;

import com.copaw.model.agent.AgentProfileConfig;
import com.copaw.service.AgentService;
import com.copaw.service.ProviderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent Manager - manages multiple CoPawAgentEngine instances.
 * 
 * Responsibilities:
 * - Manage multiple agent engine instances (one per agent config)
 * - Get/create engine instances by agent_id
 * - Support configuration hot-reload
 * - Manage chat session states (running/idle)
 */
@Service
public class AgentManager {
    
    private static final Logger log = LoggerFactory.getLogger(AgentManager.class);
    
    private final AgentService agentService;
    private final ProviderService providerService;
    private final ToolManager toolManager;
    
    // Cache of agent engines by agent_id
    private final Map<String, CoPawAgentEngine> engines = new ConcurrentHashMap<>();
    
    public AgentManager(
            AgentService agentService,
            ProviderService providerService,
            ToolManager toolManager
    ) {
        this.agentService = agentService;
        this.providerService = providerService;
        this.toolManager = toolManager;
    }
    
    /**
     * Get or create an agent engine by agent_id.
     *
     * @param agentId The agent ID
     * @return The agent engine
     */
    public CoPawAgentEngine getEngine(String agentId) {
        return engines.computeIfAbsent(agentId, this::createEngine);
    }
    
    /**
     * Create a new agent engine.
     */
    private CoPawAgentEngine createEngine(String agentId) {
        log.info("Creating engine for agent: {}", agentId);
        
        AgentProfileConfig config = agentService.getAgentConfig(agentId);
        
        return new CoPawAgentEngine(config, providerService, toolManager);
    }
    
    /**
     * Reload an agent engine (after configuration change).
     *
     * @param agentId The agent ID to reload
     * @return The new engine instance
     */
    public CoPawAgentEngine reloadEngine(String agentId) {
        log.info("Reloading engine for agent: {}", agentId);
        
        // Stop existing engine if running
        CoPawAgentEngine existing = engines.get(agentId);
        if (existing != null && existing.isRunning()) {
            existing.interrupt();
        }
        
        // Remove from cache
        engines.remove(agentId);
        
        // Create new engine
        return getEngine(agentId);
    }
    
    /**
     * Check if an agent is currently running.
     *
     * @param agentId The agent ID
     * @return true if running
     */
    public boolean isRunning(String agentId) {
        CoPawAgentEngine engine = engines.get(agentId);
        return engine != null && engine.isRunning();
    }
    
    /**
     * Interrupt an agent's current processing.
     *
     * @param agentId The agent ID
     */
    public void interrupt(String agentId) {
        CoPawAgentEngine engine = engines.get(agentId);
        if (engine != null) {
            engine.interrupt();
        }
    }
    
    /**
     * Get the workspace directory for an agent.
     *
     * @param agentId The agent ID
     * @return The workspace directory path
     */
    public String getWorkspaceDir(String agentId) {
        CoPawAgentEngine engine = engines.get(agentId);
        if (engine != null) {
            return engine.getWorkspaceDir();
        }
        
        // Load from config without creating engine
        AgentProfileConfig config = agentService.getAgentConfig(agentId);
        return config.getWorkspaceDir();
    }
    
    /**
     * Shutdown all engines.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down all agent engines");
        
        for (Map.Entry<String, CoPawAgentEngine> entry : engines.entrySet()) {
            try {
                entry.getValue().interrupt();
            } catch (Exception e) {
                log.warn("Error shutting down engine: {}", entry.getKey(), e);
            }
        }
        
        engines.clear();
    }
    
    /**
     * Get the number of active engines.
     */
    public int getActiveEngineCount() {
        return engines.size();
    }
}
