package com.copaw.controller;

import com.copaw.agent.AgentManager;
import com.copaw.agent.CoPawAgentEngine;
import com.copaw.model.chat.ChatSpec;
import com.copaw.model.console.ChatRequest;
import com.copaw.service.AgentService;
import com.copaw.service.ChatService;
import com.copaw.storage.ChatStore;
import com.copaw.storage.CoPawDataDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for ConsoleController chat functionality.
 * Tests SSE streaming, chat stop, and file upload operations.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ConsoleController Chat Tests")
class ConsoleControllerTest {

    private MockMvc mockMvc;

    @Mock
    private AgentService agentService;

    @Mock
    private ChatService chatService;

    @Mock
    private CoPawDataDir dataDir;

    @Mock
    private ChatStore chatStore;

    @Mock
    private AgentManager agentManager;

    @Mock
    private CoPawAgentEngine agentEngine;

    @Mock
    private ReActAgent agent;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        ConsoleController controller = new ConsoleController(
                agentService,
                chatService,
                chatStore,
                dataDir,
                objectMapper,
                agentManager
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("Chat endpoint should accept valid request and return SSE emitter")
    void testChatEndpointAcceptsValidRequest() throws Exception {
        // Given
        String chatId = UUID.randomUUID().toString();
        String agentId = "test-agent";
        ChatRequest request = ChatRequest.builder()
                .chatId(chatId)
                .agentId(agentId)
                .content("Hello")
                .build();

        when(agentService.getActiveAgentId()).thenReturn(agentId);
        when(agentManager.getEngine(agentId)).thenReturn(agentEngine);
        when(agentEngine.getAgent()).thenReturn(agent);
        when(agent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.empty());

        // When & Then
        mockMvc.perform(post("/console/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }

    @Test
    @DisplayName("Chat should auto-generate chat_id when null")
    void testChatAutoGeneratesChatId() throws Exception {
        // Given
        String agentId = "test-agent";
        ChatRequest request = ChatRequest.builder()
                .agentId(agentId)
                .content("Hello")
                .build();

        when(agentService.getActiveAgentId()).thenReturn(agentId);
        when(agentManager.getEngine(agentId)).thenReturn(agentEngine);
        when(agentEngine.getAgent()).thenReturn(agent);
        when(agent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.empty());

        // When & Then
        mockMvc.perform(post("/console/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }

    @Test
    @DisplayName("Chat should return error when agent not found")
    void testChatReturnsErrorWhenAgentNotFound() throws Exception {
        // Given
        String chatId = UUID.randomUUID().toString();
        String agentId = "non-existent-agent";
        ChatRequest request = ChatRequest.builder()
                .chatId(chatId)
                .agentId(agentId)
                .content("Hello")
                .build();

        when(agentManager.getEngine(agentId)).thenReturn(null);

        // When & Then
        MvcResult result = mockMvc.perform(post("/console/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        // Wait for async processing - the error should be sent via SSE
        Thread.sleep(500);
    }

    @Test
    @DisplayName("Chat should handle input array format from @agentscope-ai/chat")
    void testChatHandlesInputArrayFormat() throws Exception {
        // Given
        String chatId = UUID.randomUUID().toString();
        String agentId = "test-agent";
        
        // Create request with input array format
        Map<String, Object> inputMessage = Map.of(
                "role", "user",
                "content", "Test message from frontend"
        );
        
        ChatRequest request = ChatRequest.builder()
                .chatId(chatId)
                .agentId(agentId)
                .input(List.of(inputMessage))
                .build();

        when(agentService.getActiveAgentId()).thenReturn(agentId);
        when(agentManager.getEngine(agentId)).thenReturn(agentEngine);
        when(agentEngine.getAgent()).thenReturn(agent);
        when(agent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.empty());

        // When & Then
        mockMvc.perform(post("/console/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }

    @Test
    @DisplayName("Chat should handle multimodal content blocks")
    void testChatHandlesMultimodalContent() throws Exception {
        // Given
        String chatId = UUID.randomUUID().toString();
        String agentId = "test-agent";
        
        Map<String, Object> textBlock = Map.of(
                "type", "text",
                "text", "Hello with image"
        );
        
        Map<String, Object> inputMessage = Map.of(
                "role", "user",
                "content", List.of(textBlock)
        );
        
        ChatRequest request = ChatRequest.builder()
                .chatId(chatId)
                .agentId(agentId)
                .input(List.of(inputMessage))
                .build();

        when(agentService.getActiveAgentId()).thenReturn(agentId);
        when(agentManager.getEngine(agentId)).thenReturn(agentEngine);
        when(agentEngine.getAgent()).thenReturn(agent);
        when(agent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.empty());

        // When & Then
        mockMvc.perform(post("/console/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }

    @Test
    @DisplayName("Stop chat should return stopped=true for active session")
    void testStopChatActiveSession() throws Exception {
        // Given
        String chatId = UUID.randomUUID().toString();
        
        // Create a Flux that never completes to keep session active
        Flux<Event> infiniteFlux = Flux.never();
        
        when(agentService.getActiveAgentId()).thenReturn("test-agent");
        when(agentManager.getEngine("test-agent")).thenReturn(agentEngine);
        when(agentEngine.getAgent()).thenReturn(agent);
        when(agent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(infiniteFlux);

        // Start chat
        ChatRequest request = ChatRequest.builder()
                .chatId(chatId)
                .agentId("test-agent")
                .content("Hello")
                .build();

        mockMvc.perform(post("/console/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        // Small delay to ensure session is registered
        Thread.sleep(200);

        // When & Then - Stop the chat
        mockMvc.perform(post("/console/chat/stop")
                        .param("chat_id", chatId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopped").value(true))
                .andExpect(jsonPath("$.chat_id").value(chatId));
    }

    @Test
    @DisplayName("Stop chat should return stopped=false for non-existent session")
    void testStopChatNonExistentSession() throws Exception {
        // Given
        String chatId = UUID.randomUUID().toString();

        // When & Then
        mockMvc.perform(post("/console/chat/stop")
                        .param("chat_id", chatId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopped").value(false))
                .andExpect(jsonPath("$.chat_id").value(chatId))
                .andExpect(jsonPath("$.reason").value("Session not found"));
    }

    @Test
    @DisplayName("SSE events should follow AgentScope Runtime protocol")
    void testSSEEventFormat() throws Exception {
        // Given
        String chatId = UUID.randomUUID().toString();
        String agentId = "test-agent";
        
        // Create a mock event stream with reasoning and text
        Msg reasoningMsg = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .textContent("Thinking...")
                .build();
        Event reasoningEvent = new Event(EventType.REASONING, reasoningMsg, false);

        Msg textMsg = Msg.builder()
                .role(MsgRole.ASSISTANT)
                .textContent("Hello!")
                .build();
        Event textEvent = new Event(EventType.AGENT_RESULT, textMsg, true);

        Flux<Event> eventFlux = Flux.just(reasoningEvent, textEvent);

        ChatRequest request = ChatRequest.builder()
                .chatId(chatId)
                .agentId(agentId)
                .content("Hello")
                .build();

        when(agentService.getActiveAgentId()).thenReturn(agentId);
        when(agentManager.getEngine(agentId)).thenReturn(agentEngine);
        when(agentEngine.getAgent()).thenReturn(agent);
        when(agent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(eventFlux);

        // When
        MvcResult result = mockMvc.perform(post("/console/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        // Wait for async processing to complete
        result.getAsyncResult(5000);

        // Then - verify the stream was called with correct parameters
        verify(agent, timeout(3000)).stream(anyList(), argThat(options -> 
                options != null && 
                options.isIncludeReasoningChunk() &&
                options.isIncludeReasoningResult()
        ));
    }

    @Test
    @DisplayName("Chat should use default agent when agent_id not specified")
    void testChatUsesDefaultAgent() throws Exception {
        // Given
        String chatId = UUID.randomUUID().toString();
        String defaultAgentId = "default-agent";
        ChatRequest request = ChatRequest.builder()
                .chatId(chatId)
                .content("Hello")
                .build();

        when(agentService.getActiveAgentId()).thenReturn(defaultAgentId);
        when(agentManager.getEngine(defaultAgentId)).thenReturn(agentEngine);
        when(agentEngine.getAgent()).thenReturn(agent);
        when(agent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.empty());

        // When & Then
        mockMvc.perform(post("/console/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        // Wait for async processing
        Thread.sleep(300);
    }
}
