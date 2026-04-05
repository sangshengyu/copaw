package com.copaw.controller;

import com.copaw.model.console.ChatRequest;
import com.copaw.model.console.SSEEvent;
import com.copaw.model.console.UploadResponse;
import com.copaw.service.AgentService;
import com.copaw.service.ChatService;
import com.copaw.storage.CoPawDataDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    public ConsoleController(
            AgentService agentService,
            ChatService chatService,
            CoPawDataDir dataDir,
            ObjectMapper objectMapper) {
        this.agentService = agentService;
        this.chatService = chatService;
        this.dataDir = dataDir;
        this.objectMapper = objectMapper;
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
        String chatId = request.getChatId();
        String agentId = request.getAgentId() != null ? request.getAgentId() : agentService.getActiveAgentId();

        executorService.execute(() -> {
            try {
                log.info("Starting chat stream for chat_id: {}, agent_id: {}", chatId, agentId);

                // TODO: Implement full streaming with AgentScope hooks
                // For now, send a simple text response to maintain compatibility

                // Send thinking event
                sendEvent(emitter, SSEEvent.thinking("Processing your message...", false));

                // TODO: Integrate with CoPawAgentEngine for actual processing
                // This is a placeholder implementation
                Thread.sleep(500); // Simulate processing

                // Send text response
                sendEvent(emitter, SSEEvent.text("This is a placeholder response. Full implementation pending.", false));

                // Send done event
                sendEvent(emitter, SSEEvent.done());

                emitter.complete();
                log.info("Completed chat stream for chat_id: {}", chatId);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Chat stream interrupted for chat_id: {}", chatId);
                sendEvent(emitter, SSEEvent.error("Chat interrupted"));
                emitter.completeWithError(e);
            } catch (Exception e) {
                log.error("Error in chat stream for chat_id: {}", chatId, e);
                sendEvent(emitter, SSEEvent.error("Error: " + e.getMessage()));
                emitter.completeWithError(e);
            }
        });

        // Handle client disconnect
        emitter.onCompletion(() -> log.debug("SSE completed for chat_id: {}", chatId));
        emitter.onTimeout(() -> log.warn("SSE timeout for chat_id: {}", chatId));
        emitter.onError((e) -> log.error("SSE error for chat_id: {}", chatId, e));

        return emitter;
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
        // TODO: Implement chat stop logic with task tracker
        return ResponseEntity.ok(Map.of("stopped", true, "chat_id", chatId));
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
     * Helper method to send SSE event.
     */
    private void sendEvent(SseEmitter emitter, SSEEvent event) {
        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event()
                    .name("message")
                    .data(objectMapper.writeValueAsString(event));
            emitter.send(builder);
        } catch (IOException e) {
            log.error("Failed to send SSE event", e);
        }
    }
}
