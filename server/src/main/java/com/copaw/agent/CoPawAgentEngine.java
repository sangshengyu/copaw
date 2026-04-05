package com.copaw.agent;

import com.copaw.model.agent.AgentProfileConfig;
import com.copaw.model.config.AgentsRunningConfig;
import com.copaw.service.AgentService;
import com.copaw.service.ProviderService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.message.Msg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CoPaw Agent Engine - wraps agentscope-java's ReActAgent.
 * 
 * Encapsulates the ReActAgent with:
 * - DashScope/OpenAI model integration
 * - Dynamic model switching
 * - Memory management
 * - Tool integration
 * - Streaming hooks
 */
public class CoPawAgentEngine {
    
    private static final Logger log = LoggerFactory.getLogger(CoPawAgentEngine.class);
    
    private final String agentId;
    private final AgentProfileConfig agentConfig;
    private final ReActAgent agent;
    private final Memory memory;
    private final Toolkit toolkit;
    private final Model model;
    private final String workspaceDir;
    
    // Session state tracking
    private volatile boolean running = false;
    private volatile Thread currentThread = null;
    
    /**
     * Create a new CoPawAgentEngine.
     *
     * @param agentConfig Agent configuration
     * @param providerService Provider service for model creation
     * @param toolManager Tool manager for toolkit creation
     */
    public CoPawAgentEngine(
            AgentProfileConfig agentConfig,
            ProviderService providerService,
            ToolManager toolManager
    ) {
        this.agentId = agentConfig.getId();
        this.agentConfig = agentConfig;
        this.workspaceDir = agentConfig.getWorkspaceDir();
        
        // Create memory
        this.memory = new InMemoryMemory();
        
        // Create toolkit
        this.toolkit = toolManager.createToolkit(workspaceDir, null);
        
        // Create model
        this.model = createModel(agentConfig, providerService);
        
        // Build system prompt
        String systemPrompt = buildSystemPrompt();
        
        // Get max iterations from config
        AgentsRunningConfig runningConfig = agentConfig.getRunning();
        int maxIters = runningConfig != null && runningConfig.getMaxIters() != null
                ? runningConfig.getMaxIters()
                : 100;
        
        // Create ReActAgent
        this.agent = ReActAgent.builder()
                .name("Friday")
                .model(model)
                .sysPrompt(systemPrompt)
                .toolkit(toolkit)
                .memory(memory)
                .maxIters(maxIters)
                .build();
        
        log.info("Created CoPawAgentEngine for agent: {} with model: {}", 
                agentId, model.getClass().getSimpleName());
    }
    
    /**
     * Create the appropriate chat model based on configuration.
     */
    private Model createModel(AgentProfileConfig config, ProviderService providerService) {
        String providerId = config.getActiveModel() != null 
                ? config.getActiveModel().getProviderId() 
                : "dashscope";
        String modelName = config.getActiveModel() != null 
                ? config.getActiveModel().getModel() 
                : "qwen3-max";
        
        // Get API key from provider service
        // TODO: Get actual API key from ProviderService
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null) {
            apiKey = "sk-placeholder"; // Placeholder for compilation
        }
        
        if ("dashscope".equals(providerId)) {
            return DashScopeChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .build();
        } else {
            // Default to OpenAI-compatible endpoint
            return OpenAIChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .baseUrl(providerService.getProvider(providerId) != null 
                            ? providerService.getProvider(providerId).getBaseUrl() 
                            : null)
                    .build();
        }
    }
    
    /**
     * Build system prompt from workspace files.
     */
    private String buildSystemPrompt() {
        StringBuilder prompt = new StringBuilder();
        
        // Add agent identity
        prompt.append("# Agent Identity\n\n");
        prompt.append("Your agent id is `").append(agentId).append("`.\n\n");
        
        // Read prompt files
        List<String> promptFiles = agentConfig.getSystemPromptFiles();
        if (promptFiles == null || promptFiles.isEmpty()) {
            promptFiles = List.of("AGENTS.md", "SOUL.md", "PROFILE.md");
        }
        
        for (String filename : promptFiles) {
            Path filePath = Path.of(workspaceDir, filename);
            if (Files.exists(filePath)) {
                try {
                    String content = Files.readString(filePath);
                    // Remove YAML frontmatter if present
                    if (content.startsWith("---")) {
                        int endIndex = content.indexOf("---", 3);
                        if (endIndex > 0) {
                            content = content.substring(endIndex + 3).trim();
                        }
                    }
                    if (!content.isEmpty()) {
                        prompt.append("\n\n# ").append(filename).append("\n\n");
                        prompt.append(content);
                    }
                } catch (Exception e) {
                    log.warn("Failed to read prompt file: {}", filename, e);
                }
            }
        }
        
        return prompt.toString();
    }
    
    /**
     * Process a user message and return response.
     *
     * @param userMessage The user message
     * @param sessionId Session ID for conversation continuity
     * @param userId User ID
     * @return Response message
     */
    public Msg processMessage(Msg userMessage, String sessionId, String userId) {
        running = true;
        currentThread = Thread.currentThread();
        
        try {
            log.info("Processing message for agent: {}, session: {}", agentId, sessionId);
            
            // Use reactive call method and block for synchronous result
            Msg response = agent.call(java.util.List.of(userMessage)).block();
            
            return response;
            
        } catch (Exception e) {
            log.error("Error processing message for agent: {}", agentId, e);
            throw new RuntimeException("Agent processing failed: " + e.getMessage(), e);
        } finally {
            running = false;
            currentThread = null;
        }
    }
    
    /**
     * Interrupt the current processing.
     */
    public void interrupt() {
        running = false;
        if (currentThread != null) {
            currentThread.interrupt();
        }
        log.info("Interrupted agent: {}", agentId);
    }
    
    /**
     * Check if agent is currently running.
     */
    public boolean isRunning() {
        return running;
    }
    
    /**
     * Get the agent ID.
     */
    public String getAgentId() {
        return agentId;
    }
    
    /**
     * Get the workspace directory.
     */
    public String getWorkspaceDir() {
        return workspaceDir;
    }
    
    /**
     * Get the memory.
     */
    public Memory getMemory() {
        return memory;
    }
    
    /**
     * Get the underlying ReActAgent.
     */
    public ReActAgent getAgent() {
        return agent;
    }
}
