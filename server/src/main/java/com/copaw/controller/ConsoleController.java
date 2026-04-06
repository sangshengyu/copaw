package com.copaw.controller;

import com.copaw.agent.AgentManager;
import com.copaw.agent.CoPawAgentEngine;
import com.copaw.model.chat.ChatHistory;
import com.copaw.model.chat.ChatSpec;
import com.copaw.model.chat.Message;
import com.copaw.model.console.ChatRequest;
import com.copaw.model.console.SSEEvent;
import com.copaw.model.console.UploadResponse;
import com.copaw.service.AgentService;
import com.copaw.service.ChatService;
import com.copaw.storage.ChatStore;
import com.copaw.storage.CoPawDataDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * Console API controller for chat and file operations.
 * Provides SSE streaming chat endpoint compatible with Python version.
 */
@RestController
@RequestMapping("/console")
public class ConsoleController {

    private static final Logger log = LoggerFactory.getLogger(ConsoleController.class);
    private static final long SSE_TIMEOUT = 300_000L; // 5 minutes

    private final AgentService agentService;
    private final ChatService chatService;
    private final ChatStore chatStore;
    private final CoPawDataDir dataDir;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;
    private final AgentManager agentManager;

    // Track active chat sessions for cancellation support
    private final ConcurrentHashMap<String, ChatSession> activeSessions = new ConcurrentHashMap<>();

    public ConsoleController(
            AgentService agentService,
            ChatService chatService,
            ChatStore chatStore,
            CoPawDataDir dataDir,
            ObjectMapper objectMapper,
            AgentManager agentManager) {
        this.agentService = agentService;
        this.chatService = chatService;
        this.chatStore = chatStore;
        this.dataDir = dataDir;
        this.objectMapper = objectMapper;
        this.agentManager = agentManager;
        this.executorService = Executors.newCachedThreadPool();
    }

    /**
     * SSE streaming chat endpoint.
     * POST /console/chat
     *
     * @param request Chat request with messages
     * @return SSE emitter for streaming response
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);
        // Resolve chat ID: like Python, session_id is used for lookup, chat_id (UUID) is the primary key.
        // The frontend sends session_id (a local timestamp like "1775463969926").
        // We must generate a proper UUID for chat_id, and keep session_id as-is for mapping.
        String sessionIdFromRequest = request.getSessionId();
        String userId = request.getUserId();
        String channel = request.getChannel();
        String chatId;
        if (request.getChatId() != null && !request.getChatId().isEmpty()) {
            chatId = request.getChatId();
        } else if (sessionIdFromRequest != null && !sessionIdFromRequest.isEmpty()) {
            // Look up existing chat by session_id+user_id+channel (Python's get_or_create_chat pattern)
            ChatSpec existingBySession = chatService.getChatBySessionId(
                    sessionIdFromRequest,
                    userId != null ? userId : "default",
                    channel != null ? channel : "console");
            if (existingBySession != null) {
                chatId = existingBySession.getId();
            } else {
                chatId = UUID.randomUUID().toString();
            }
        } else {
            chatId = UUID.randomUUID().toString();
        }
        final String finalChatId = chatId;
        String agentId = request.getAgentId() != null ? request.getAgentId() : agentService.getActiveAgentId();
        // Extract user content from request (supports both formats)
        String content = extractUserContent(request);

        executorService.execute(() -> {
            AtomicReference<ChatSpec> chatSpecRef = new AtomicReference<>();
            try {
                log.info("Starting chat stream for chat_id: {}, agent_id: {}", finalChatId, agentId);

                // Get or create chat session (pass session_id separately so it's preserved in ChatSpec)
                ChatSpec chatSpec = getOrCreateChat(finalChatId, sessionIdFromRequest, agentId, content, userId, channel);
                chatSpecRef.set(chatSpec);
                String sessionId = chatSpec.getSessionId();

                // Get agent engine
                CoPawAgentEngine engine = agentManager.getEngine(agentId);
                if (engine == null) {
                    throw new RuntimeException("Agent engine not found for agent: " + agentId);
                }

                // Build user message
                Msg userMessage = Msg.builder()
                        .role(MsgRole.USER)
                        .textContent(content)
                        .build();

                // Create stream options to capture all event types
                StreamOptions streamOptions = StreamOptions.builder()
                        .includeReasoningChunk(true)
                        .includeReasoningResult(true)
                        .includeActingChunk(true)
                        .includeSummaryChunk(true)
                        .includeSummaryResult(true)
                        .build();

                // Get the agent and stream
                var agent = engine.getAgent();
                Flux<Event> eventFlux = agent.stream(List.of(userMessage), streamOptions);

                // Track ALL messages in order for responseCompleted (matches Python behavior)
                final List<Map<String, Object>> allCompletedMessages =
                        java.util.Collections.synchronizedList(new ArrayList<>());

                // Current reasoning phase tracking (reset after each tool call)
                final AtomicReference<String> currentReasoningMsgId = new AtomicReference<>();
                final StringBuilder currentReasoningContent = new StringBuilder();

                // Final text message tracking
                final AtomicReference<String> textMsgIdRef = new AtomicReference<>();
                final StringBuilder textContent = new StringBuilder();

                // Full history for saving (tool calls + results)
                final List<Map<String, String>> toolCallHistory =
                        java.util.Collections.synchronizedList(new ArrayList<>());
                final List<Map<String, String>> toolResultHistory =
                        java.util.Collections.synchronizedList(new ArrayList<>());
                final StringBuilder fullReasoningContent = new StringBuilder();

                // Subscribe to the stream and convert events to SSE
                var disposable = eventFlux.subscribe(
                        event -> {
                            if (event == null || event.getMessage() == null) return;
                            EventType eventType = event.getType();
                            Msg message = event.getMessage();
                            boolean isLast = event.isLast();

                            switch (eventType) {
                                case REASONING -> {
                                    List<ContentBlock> contentBlocks = message.getContent();
                                    if (contentBlocks != null) {
                                        for (ContentBlock block : contentBlocks) {
                                            if (block instanceof ThinkingBlock thinkingBlock) {
                                                String thinking = thinkingBlock.getThinking();
                                                if (thinking != null && !thinking.isEmpty()) {
                                                    fullReasoningContent.append(thinking);
                                                    currentReasoningContent.append(thinking);
                                                    if (currentReasoningMsgId.get() == null) {
                                                        String msgId = "msg_" + UUID.randomUUID();
                                                        currentReasoningMsgId.set(msgId);
                                                        sendEvent(emitter, SSEEvent.newMessage(msgId, "reasoning", thinking));
                                                    } else {
                                                        sendEvent(emitter, SSEEvent.contentDelta(currentReasoningMsgId.get(), thinking));
                                                    }
                                                }
                                            } else if (block instanceof TextBlock textBlock) {
                                                String text = textBlock.getText();
                                                // Filter out internal markers like __fragment__
                                                if (text != null && !text.isEmpty()
                                                        && !text.startsWith("__") && !text.endsWith("__")) {
                                                    fullReasoningContent.append(text);
                                                    currentReasoningContent.append(text);
                                                    if (currentReasoningMsgId.get() == null) {
                                                        String msgId = "msg_" + UUID.randomUUID();
                                                        currentReasoningMsgId.set(msgId);
                                                        sendEvent(emitter, SSEEvent.newMessage(msgId, "reasoning", text));
                                                    } else {
                                                        sendEvent(emitter, SSEEvent.contentDelta(currentReasoningMsgId.get(), text));
                                                    }
                                                }
                                            } else if (block instanceof ToolUseBlock toolUseBlock) {
                                                // Filter internal tool markers (e.g., __fragment__)
                                                String toolName = toolUseBlock.getName();
                                                if (toolName != null && toolName.startsWith("__") && toolName.endsWith("__")) {
                                                    log.debug("Skipping internal tool marker: {}", toolName);
                                                    continue;
                                                }
                                                // Complete current reasoning phase before tool call
                                                if (currentReasoningMsgId.get() != null) {
                                                    Map<String, Object> completedReasoning = SSEEvent.messageCompleted(
                                                            currentReasoningMsgId.get(), "reasoning",
                                                            currentReasoningContent.toString());
                                                    sendEvent(emitter, completedReasoning);
                                                    allCompletedMessages.add(completedReasoning);
                                                    // Reset for next reasoning phase
                                                    currentReasoningMsgId.set(null);
                                                    currentReasoningContent.setLength(0);
                                                }

                                                // Send tool call message
                                                String msgId = "msg_" + UUID.randomUUID();
                                                String callId = toolUseBlock.getId();
                                                String name = toolUseBlock.getName();
                                                Map<String, Object> input = toolUseBlock.getInput();
                                                String arguments;
                                                try {
                                                    arguments = objectMapper.writeValueAsString(input);
                                                } catch (JsonProcessingException e) {
                                                    arguments = input != null ? input.toString() : "";
                                                }
                                                Map<String, Object> toolCall = SSEEvent.toolCallMessage(msgId, callId, name, arguments);
                                                sendEvent(emitter, toolCall);
                                                allCompletedMessages.add(toolCall);
                                                toolCallHistory.add(Map.of(
                                                        "callId", callId != null ? callId : "",
                                                        "name", name != null ? name : "",
                                                        "arguments", arguments != null ? arguments : ""));
                                            }
                                        }
                                    }
                                    // If this is the last REASONING event and we have pending reasoning content
                                    if (isLast && currentReasoningMsgId.get() != null) {
                                        Map<String, Object> completedReasoning = SSEEvent.messageCompleted(
                                                currentReasoningMsgId.get(), "reasoning",
                                                currentReasoningContent.toString());
                                        sendEvent(emitter, completedReasoning);
                                        allCompletedMessages.add(completedReasoning);
                                        currentReasoningMsgId.set(null);
                                        currentReasoningContent.setLength(0);
                                    }
                                }
                                case TOOL_RESULT -> {
                                    List<ContentBlock> contentBlocks = message.getContent();
                                    if (contentBlocks != null) {
                                        for (ContentBlock block : contentBlocks) {
                                            if (block instanceof ToolResultBlock toolResultBlock) {
                                                String msgId = "msg_" + UUID.randomUUID();
                                                String callId = toolResultBlock.getId();
                                                String name = toolResultBlock.getName();

                                                // Filter internal tool markers (e.g., __fragment__)
                                                if (name != null && name.startsWith("__") && name.endsWith("__")) {
                                                    continue;
                                                }

                                                List<ContentBlock> outputBlocks = toolResultBlock.getOutput();
                                                StringBuilder outputBuilder = new StringBuilder();
                                                if (outputBlocks != null) {
                                                    for (ContentBlock outputBlock : outputBlocks) {
                                                        if (outputBlock instanceof TextBlock textBlock) {
                                                            outputBuilder.append(textBlock.getText());
                                                        } else {
                                                            try {
                                                                outputBuilder.append(objectMapper.writeValueAsString(outputBlock));
                                                            } catch (JsonProcessingException e) {
                                                                outputBuilder.append(outputBlock.toString());
                                                            }
                                                        }
                                                    }
                                                }
                                                String output = outputBuilder.toString();
                                                Map<String, Object> toolResult = SSEEvent.toolResultMessage(msgId, callId, name, output);
                                                sendEvent(emitter, toolResult);
                                                allCompletedMessages.add(toolResult);
                                                toolResultHistory.add(Map.of(
                                                        "callId", callId != null ? callId : "",
                                                        "name", name != null ? name : "",
                                                        "output", output));
                                            }
                                        }
                                    }
                                }
                                case AGENT_RESULT, SUMMARY -> {
                                    // Final text output from agent
                                    String text = message.getTextContent();
                                    if (text != null && !text.isEmpty()) {
                                        textContent.append(text);
                                        if (textMsgIdRef.get() == null) {
                                            String msgId = "msg_" + UUID.randomUUID();
                                            textMsgIdRef.set(msgId);
                                            sendEvent(emitter, SSEEvent.newMessage(msgId, "message", text));
                                        } else {
                                            sendEvent(emitter, SSEEvent.contentDelta(textMsgIdRef.get(), text));
                                        }
                                        if (isLast) {
                                            sendEvent(emitter, SSEEvent.messageCompleted(
                                                    textMsgIdRef.get(), "message", textContent.toString()));
                                        }
                                    }
                                }
                                default -> {
                                    // HINT and other internal events – skip (not user-visible)
                                    log.debug("Skipping non-visible event type: {}", eventType);
                                }
                            }
                        },
                        error -> {
                            log.error("Error in stream for chat_id: {}", finalChatId, error);
                            String friendlyMessage = toFriendlyErrorMessage(error);
                            sendEvent(emitter, SSEEvent.responseFailed(friendlyMessage));
                            emitter.completeWithError(error);
                            activeSessions.remove(finalChatId);
                        },
                        () -> {
                            // Complete any remaining reasoning message
                            if (currentReasoningMsgId.get() != null) {
                                Map<String, Object> completedReasoning = SSEEvent.messageCompleted(
                                        currentReasoningMsgId.get(), "reasoning",
                                        currentReasoningContent.toString());
                                allCompletedMessages.add(completedReasoning);
                            }
                            // Complete text message
                            if (textMsgIdRef.get() != null) {
                                allCompletedMessages.add(SSEEvent.messageCompleted(
                                        textMsgIdRef.get(), "message", textContent.toString()));
                            }
                            sendEvent(emitter, SSEEvent.responseCompleted(allCompletedMessages));
                            emitter.complete();
                            log.info("Completed chat stream for chat_id: {}", finalChatId);

                            // Save complete chat history
                            ChatSpec finalChatSpec = chatSpecRef.get();
                            if (finalChatSpec != null) {
                                saveChatHistory(agentId, finalChatSpec.getSessionId(), content,
                                        fullReasoningContent.toString(), textContent.toString(),
                                        toolCallHistory, toolResultHistory);
                            }

                            activeSessions.remove(finalChatId);
                        }
                );

                // Store the session for cancellation support
                activeSessions.put(finalChatId, new ChatSession(finalChatId, emitter, disposable));

            } catch (Exception e) {
                log.error("Error in chat stream for chat_id: {}", finalChatId, e);
                String friendlyMessage = toFriendlyErrorMessage(e);
                sendEvent(emitter, SSEEvent.responseFailed(friendlyMessage));
                emitter.completeWithError(e);
                activeSessions.remove(finalChatId);
            }
        });

        // Handle client disconnect
        emitter.onCompletion(() -> {
            log.debug("SSE completed for chat_id: {}", finalChatId);
            activeSessions.remove(finalChatId);
        });
        emitter.onTimeout(() -> {
            log.warn("SSE timeout for chat_id: {}", finalChatId);
            activeSessions.remove(finalChatId);
        });
        emitter.onError((e) -> {
            log.error("SSE error for chat_id: {}", finalChatId, e);
            activeSessions.remove(finalChatId);
        });

        return emitter;
    }

    /**
     * Extract user message content from request.
     * Supports two formats:
     * 1. New format (from @agentscope-ai/chat): input array with role and content
     * 2. Legacy format: direct content string
     *
     * @param request Chat request
     * @return Extracted text content or null
     */
    @SuppressWarnings("unchecked")
    private String extractUserContent(ChatRequest request) {
        // Try to extract from input array (new format)
        List<Map<String, Object>> input = request.getInput();
        if (input != null && !input.isEmpty()) {
            // Find the last user message
            String lastUserContent = null;
            for (Map<String, Object> msg : input) {
                Object role = msg.get("role");
                if ("user".equals(role)) {
                    Object msgContent = msg.get("content");
                    if (msgContent instanceof String) {
                        lastUserContent = (String) msgContent;
                    } else if (msgContent instanceof List) {
                        // Content is array of blocks, find text block
                        List<Map<String, Object>> blocks = (List<Map<String, Object>>) msgContent;
                        for (Map<String, Object> block : blocks) {
                            if ("text".equals(block.get("type"))) {
                                Object text = block.get("text");
                                if (text instanceof String) {
                                    lastUserContent = (String) text;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
            if (lastUserContent != null) {
                return lastUserContent;
            }
        }

        // Fallback to legacy content field
        return request.getContent();
    }


    /**
     * Stop an ongoing chat.
     * POST /console/chat/stop?chat_id={chat_id}
     *
     * @param chatId Chat ID to stop
     * @return Success response
     */
    @PostMapping("/chat/stop")
    public ResponseEntity<Map<String, Object>> stopChat(@RequestParam("chat_id") String chatId) {
        log.info("Stopping chat: {}", chatId);

        ChatSession session = activeSessions.get(chatId);
        if (session == null) {
            log.warn("No active chat session found for chat_id: {}", chatId);
            return ResponseEntity.ok(Map.of("stopped", false, "chat_id", chatId, "reason", "Session not found"));
        }

        try {
            // Cancel the Flux subscription
            if (session.disposable() != null && !session.disposable().isDisposed()) {
                session.disposable().dispose();
                log.debug("Disposed Flux subscription for chat_id: {}", chatId);
            }

            // Complete the SSE emitter
            if (session.emitter() != null) {
                try {
                    session.emitter().complete();
                    log.debug("Completed SSE emitter for chat_id: {}", chatId);
                } catch (Exception e) {
                    log.warn("Error completing SSE emitter for chat_id: {}", chatId, e);
                }
            }

            // Remove from active sessions
            activeSessions.remove(chatId);
            log.info("Successfully stopped chat: {}", chatId);

            return ResponseEntity.ok(Map.of("stopped", true, "chat_id", chatId));
        } catch (Exception e) {
            log.error("Error stopping chat: {}", chatId, e);
            return ResponseEntity.ok(Map.of("stopped", false, "chat_id", chatId, "error", e.getMessage()));
        }
    }

    /**
     * Upload a file to the agent's workspace.
     * POST /console/upload
     *
     * @param file     Multipart file
     * @param agentId  Optional agent ID
     * @return Upload response with file details
     */
    @PostMapping("/upload")
    public ResponseEntity<UploadResponse> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "agent_id", required = false) String agentId) {

        try {
            String targetAgentId = agentId != null ? agentId : agentService.getActiveAgentId();
            String workspaceDir = agentService.getAgentWorkspaceDir(targetAgentId);

            // Generate stored filename with UUID
            String originalFilename = file.getOriginalFilename();
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            String storedName = UUID.randomUUID().toString() + extension;

            // Ensure uploads directory exists
            Path uploadsDir = Path.of(workspaceDir, "uploads");
            Files.createDirectories(uploadsDir);

            // Save file
            Path targetPath = uploadsDir.resolve(storedName);
            file.transferTo(targetPath.toFile());

            // Build URL
            String url = "/files/preview/" + storedName + "?agent_id=" + targetAgentId;

            UploadResponse response = UploadResponse.builder()
                    .url(url)
                    .fileName(originalFilename)
                    .storedName(storedName)
                    .size(file.getSize())
                    .mimeType(file.getContentType())
                    .build();

            log.info("Uploaded file: {} -> {} for agent: {}", originalFilename, storedName, targetAgentId);
            return ResponseEntity.ok(response);

        } catch (IOException e) {
            log.error("Failed to upload file", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Preview a file from the workspace.
     * GET /files/preview/{filename}
     *
     * @param filename File name
     * @param agentId  Optional agent ID
     * @return File content
     */
    @GetMapping("/files/preview/{filename}")
    public ResponseEntity<byte[]> previewFile(
            @PathVariable("filename") String filename,
            @RequestParam(value = "agent_id", required = false) String agentId) {

        try {
            String targetAgentId = agentId != null ? agentId : agentService.getActiveAgentId();
            String workspaceDir = agentService.getAgentWorkspaceDir(targetAgentId);

            Path filePath = Path.of(workspaceDir, "uploads", filename);

            if (!Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }

            // Security check: ensure file is within workspace
            if (!filePath.normalize().startsWith(Path.of(workspaceDir).normalize())) {
                return ResponseEntity.badRequest().build();
            }

            byte[] content = Files.readAllBytes(filePath);
            String contentType = Files.probeContentType(filePath);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType != null ? contentType : "application/octet-stream"))
                    .body(content);

        } catch (IOException e) {
            log.error("Failed to preview file: {}", filename, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Helper method to send SSE event (Map payload).
     */
    private void sendEvent(SseEmitter emitter, Map<String, Object> payload) {
        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event()
                    .name("message")
                    .data(objectMapper.writeValueAsString(payload));
            emitter.send(builder);
        } catch (IOException e) {
            log.error("Failed to send SSE event", e);
        }
    }

    /**
     * Get existing chat or create new one.
     * Similar to Python's get_or_create_chat() pattern.
     *
     * @param chatId  the chat ID (UUID)
     * @param agentId the agent ID
     * @param content the user message content (used for title)
     * @return the chat spec (existing or newly created)
     */
    private ChatSpec getOrCreateChat(String chatId, String sessionIdFromRequest, String agentId, String content, String userId, String channel) {
        // Try to get existing chat by UUID
        ChatSpec existing = chatService.getChat(chatId);
        if (existing != null) {
            log.debug("Found existing chat: {}", chatId);
            return existing;
        }

        // Create new chat with UUID as id, session_id preserved from frontend
        log.debug("Creating new chat for chat_id: {}, session_id: {}", chatId, sessionIdFromRequest);
        
        // Generate title from content (first 50 chars)
        String title = content != null && !content.isEmpty() 
            ? (content.length() > 50 ? content.substring(0, 50) + "..." : content)
            : "New Chat";
        
        // session_id: preserve frontend's identifier (timestamp) for mapping
        String sessionId = sessionIdFromRequest != null ? sessionIdFromRequest : chatId;
        
        Instant now = Instant.now();
        
        ChatSpec newChat = ChatSpec.builder()
                .id(chatId)
                .name(title)
                .sessionId(sessionId)
                .userId(userId != null ? userId : "default")
                .channel(channel != null ? channel : "console")
                .createdAt(now)
                .updatedAt(now)
                .status("idle")
                .build();
        
        chatStore.createChat(agentId, newChat);
        log.info("Created new chat: {} with session_id: {}, user_id: {}", chatId, sessionId, newChat.getUserId());
        
        return newChat;
    }

    /**
     * Save chat history after stream completes.
     * Saves ALL message types: user, reasoning, tool calls, tool results, and text.
     * Matches the Python version behavior for full history preservation.
     */
    private void saveChatHistory(String agentId, String sessionId, String userContent,
                                  String reasoningContent, String assistantContent,
                                  List<Map<String, String>> toolCalls,
                                  List<Map<String, String>> toolResults) {
        try {
            ChatHistory history = chatStore.loadChatHistory(agentId, sessionId);
            if (history == null) {
                history = ChatHistory.builder().build();
            }

            // Add user message
            Message userMessage = Message.builder()
                    .role("user")
                    .content(userContent)
                    .build();
            userMessage.setAdditionalField("type", "message");
            history.getMessages().add(userMessage);

            // Add reasoning message (if present)
            if (reasoningContent != null && !reasoningContent.isEmpty()) {
                Message reasoningMessage = Message.builder()
                        .role("assistant")
                        .content(reasoningContent)
                        .build();
                reasoningMessage.setAdditionalField("type", "reasoning");
                history.getMessages().add(reasoningMessage);
            }

            // Add tool call messages
            if (toolCalls != null) {
                for (Map<String, String> tc : toolCalls) {
                    Message toolCallMsg = Message.builder()
                            .role("assistant")
                            .content(List.of(Map.of(
                                    "type", "data",
                                    "data", Map.of(
                                            "call_id", tc.getOrDefault("callId", ""),
                                            "name", tc.getOrDefault("name", ""),
                                            "arguments", tc.getOrDefault("arguments", "")))))
                            .build();
                    toolCallMsg.setAdditionalField("type", "plugin_call");
                    history.getMessages().add(toolCallMsg);
                }
            }

            // Add tool result messages
            if (toolResults != null) {
                for (Map<String, String> tr : toolResults) {
                    Message toolResultMsg = Message.builder()
                            .role("assistant")
                            .content(List.of(Map.of(
                                    "type", "data",
                                    "data", Map.of(
                                            "call_id", tr.getOrDefault("callId", ""),
                                            "name", tr.getOrDefault("name", ""),
                                            "output", tr.getOrDefault("output", "")))))
                            .build();
                    toolResultMsg.setAdditionalField("type", "plugin_call_output");
                    history.getMessages().add(toolResultMsg);
                }
            }

            // Add assistant text message (if present)
            if (assistantContent != null && !assistantContent.isEmpty()) {
                Message assistantMessage = Message.builder()
                        .role("assistant")
                        .content(assistantContent)
                        .build();
                assistantMessage.setAdditionalField("type", "message");
                history.getMessages().add(assistantMessage);
            }

            chatStore.saveChatHistory(agentId, sessionId, history);
            log.debug("Saved chat history for session: {} (reasoning={}, toolCalls={}, toolResults={}, text={})",
                    sessionId,
                    reasoningContent != null && !reasoningContent.isEmpty(),
                    toolCalls != null ? toolCalls.size() : 0,
                    toolResults != null ? toolResults.size() : 0,
                    assistantContent != null && !assistantContent.isEmpty());

        } catch (Exception e) {
            log.error("Failed to save chat history for session: {}", sessionId, e);
        }
    }

    /**
     * Record to track active chat session.
     */
    private record ChatSession(String chatId, SseEmitter emitter, reactor.core.Disposable disposable) {}

    /**
     * Convert exceptions to user-friendly error messages.
     * Maps technical errors (SSL, network, timeout) to readable descriptions.
     */
    private static String toFriendlyErrorMessage(Throwable error) {
        if (error == null) return "Unknown error";

        // Unwrap common wrapper exceptions
        Throwable cause = error;
        while (cause.getCause() != null && cause != cause.getCause()) {
            cause = cause.getCause();
        }

        String msg = cause.getMessage();
        String className = cause.getClass().getSimpleName();

        // SSL / TLS errors
        if (className.contains("SSLHandshakeException") || (msg != null && msg.contains("SSL"))) {
            return "\u65e0\u6cd5\u4e0e\u6a21\u578b\u670d\u52a1\u5efa\u7acb\u5b89\u5168\u8fde\u63a5 (SSL \u63e1\u624b\u5931\u8d25)\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc\u73af\u5883\u6216\u91cd\u8bd5";
        }

        // Connection / network errors
        if (className.contains("ConnectException") || className.contains("UnknownHostException")
                || className.contains("SocketException") || className.contains("HttpTimeoutException")) {
            return "\u65e0\u6cd5\u8fde\u63a5\u5230\u6a21\u578b\u670d\u52a1\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc\u8fde\u63a5\u6216\u670d\u52a1\u5730\u5740\u914d\u7f6e";
        }

        // Retry exhausted
        if (className.contains("RetryExhaustedException") || (msg != null && msg.contains("Retries exhausted"))) {
            return "\u6a21\u578b\u670d\u52a1\u591a\u6b21\u91cd\u8bd5\u540e\u4ecd\u65e0\u6cd5\u8fde\u63a5\uff0c\u8bf7\u68c0\u67e5\u7f51\u7edc\u73af\u5883\u6216 API \u5bc6\u94a5\u914d\u7f6e";
        }

        // API key / auth errors
        if (msg != null && (msg.contains("401") || msg.contains("Unauthorized") || msg.contains("API key"))) {
            return "\u6a21\u578b API \u8ba4\u8bc1\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5 API \u5bc6\u94a5\u914d\u7f6e";
        }

        // Agent not found
        if (msg != null && msg.contains("Agent engine not found")) {
            return "\u4ee3\u7406\u672a\u521d\u59cb\u5316\uff0c\u8bf7\u68c0\u67e5\u4ee3\u7406\u914d\u7f6e\u5e76\u91cd\u65b0\u542f\u52a8";
        }

        // Generic fallback – keep it short
        return "\u670d\u52a1\u5668\u9519\u8bef: " + (msg != null ? msg : className);
    }
}
