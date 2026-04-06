package com.copaw.controller;

import com.copaw.agent.AgentManager;
import com.copaw.agent.CoPawAgentEngine;
import com.copaw.model.console.ChatRequest;
import com.copaw.model.console.SSEEvent;
import com.copaw.model.console.UploadResponse;
import com.copaw.service.AgentService;
import com.copaw.service.ChatService;
import com.copaw.storage.CoPawDataDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

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
    private final CoPawDataDir dataDir;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;
    private final AgentManager agentManager;

    // Track active chat sessions for cancellation support
    private final ConcurrentHashMap<String, ChatSession> activeSessions = new ConcurrentHashMap<>();

    public ConsoleController(
            AgentService agentService,
            ChatService chatService,
            CoPawDataDir dataDir,
            ObjectMapper objectMapper,
            AgentManager agentManager) {
        this.agentService = agentService;
        this.chatService = chatService;
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
        // Generate UUID if chat_id is null to avoid ConcurrentHashMap null key issue
        String chatId = (request.getChatId() != null && !request.getChatId().isEmpty()) 
            ? request.getChatId() 
            : UUID.randomUUID().toString();
        final String finalChatId = chatId;
        String agentId = request.getAgentId() != null ? request.getAgentId() : agentService.getActiveAgentId();
        // Extract user content from request (supports both formats)
        String content = extractUserContent(request);

        executorService.execute(() -> {
            try {
                log.info("Starting chat stream for chat_id: {}, agent_id: {}", finalChatId, agentId);

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

                // Track current message IDs and accumulated content for delta-streaming
                final AtomicReference<String> reasoningMsgIdRef = new AtomicReference<>();
                final AtomicReference<String> textMsgIdRef = new AtomicReference<>();
                final StringBuilder reasoningContent = new StringBuilder();
                final StringBuilder textContent = new StringBuilder();

                // Subscribe to the stream and convert events to SSE
                var disposable = eventFlux.subscribe(
                        event -> {
                            if (event == null || event.getMessage() == null) return;
                            EventType eventType = event.getType();
                            String text = event.getMessage().getTextContent();
                            boolean isLast = event.isLast();

                            switch (eventType) {
                                case REASONING -> {
                                    if (text != null) reasoningContent.append(text);
                                    if (reasoningMsgIdRef.get() == null) {
                                        String msgId = "msg_" + UUID.randomUUID();
                                        reasoningMsgIdRef.set(msgId);
                                        sendEvent(emitter, SSEEvent.newMessage(msgId, "reasoning", text));
                                    } else {
                                        sendEvent(emitter, SSEEvent.contentDelta(reasoningMsgIdRef.get(), text));
                                    }
                                    if (isLast) {
                                        sendEvent(emitter, SSEEvent.messageCompleted(reasoningMsgIdRef.get(), "reasoning", reasoningContent.toString()));
                                    }
                                }
                                case TOOL_RESULT -> {
                                    String toolMsgId = "msg_" + UUID.randomUUID();
                                    sendEvent(emitter, SSEEvent.toolResultMessage(
                                            toolMsgId,
                                            event.getMessageId() != null ? event.getMessageId() : toolMsgId,
                                            "",
                                            text != null ? text : ""));
                                }
                                default -> {
                                    // TEXT, AGENT_RESULT, SUMMARY, HINT, etc.
                                    if (text != null) textContent.append(text);
                                    if (textMsgIdRef.get() == null) {
                                        String msgId = "msg_" + UUID.randomUUID();
                                        textMsgIdRef.set(msgId);
                                        sendEvent(emitter, SSEEvent.newMessage(msgId, "message", text));
                                    } else {
                                        sendEvent(emitter, SSEEvent.contentDelta(textMsgIdRef.get(), text));
                                    }
                                    if (isLast) {
                                        sendEvent(emitter, SSEEvent.messageCompleted(textMsgIdRef.get(), "message", textContent.toString()));
                                    }
                                }
                            }
                        },
                        error -> {
                            log.error("Error in stream for chat_id: {}", finalChatId, error);
                            sendEvent(emitter, SSEEvent.responseFailed("Stream error: " + error.getMessage()));
                            emitter.completeWithError(error);
                            activeSessions.remove(finalChatId);
                        },
                        () -> {
                            // Stream completed – build output with all completed messages
                            List<Map<String, Object>> completedMessages = new ArrayList<>();
                            if (reasoningMsgIdRef.get() != null) {
                                completedMessages.add(SSEEvent.messageCompleted(
                                        reasoningMsgIdRef.get(), "reasoning", reasoningContent.toString()));
                            }
                            if (textMsgIdRef.get() != null) {
                                completedMessages.add(SSEEvent.messageCompleted(
                                        textMsgIdRef.get(), "message", textContent.toString()));
                            }
                            sendEvent(emitter, SSEEvent.responseCompleted(completedMessages));
                            emitter.complete();
                            log.info("Completed chat stream for chat_id: {}", finalChatId);
                            activeSessions.remove(finalChatId);
                        }
                );

                // Store the session for cancellation support
                activeSessions.put(finalChatId, new ChatSession(finalChatId, emitter, disposable));

            } catch (Exception e) {
                log.error("Error in chat stream for chat_id: {}", finalChatId, e);
                sendEvent(emitter, SSEEvent.responseFailed("Error: " + e.getMessage()));
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
     * Record to track active chat session.
     */
    private record ChatSession(String chatId, SseEmitter emitter, reactor.core.Disposable disposable) {}
}
