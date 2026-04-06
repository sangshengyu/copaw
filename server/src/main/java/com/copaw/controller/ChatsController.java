package com.copaw.controller;

import com.copaw.model.chat.ChatHistory;
import com.copaw.model.chat.ChatSpec;
import com.copaw.model.chat.ChatUpdate;
import com.copaw.service.AgentService;
import com.copaw.service.ChatService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Chat management API.
 */
@RestController
@RequestMapping("/chats")
public class ChatsController {

    private final ChatService chatService;
    private final AgentService agentService;

    public ChatsController(ChatService chatService, AgentService agentService) {
        this.chatService = chatService;
        this.agentService = agentService;
    }

    private String resolveAgentId(String agentIdHeader) {
        return (agentIdHeader != null && !agentIdHeader.isBlank())
                ? agentIdHeader
                : agentService.getActiveAgentId();
    }

    /**
     * List all chats with optional filters.
     * GET /chats
     */
    @GetMapping
    public List<ChatSpec> listChats(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader,
            @RequestParam(value = "user_id", required = false) String userId,
            @RequestParam(value = "channel", required = false) String channel) {
        return chatService.listChats(resolveAgentId(agentIdHeader), userId, channel);
    }

    /**
     * Create a new chat.
     * POST /chats
     */
    @PostMapping
    public ChatSpec createChat(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader,
            @RequestBody ChatSpec spec) {
        return chatService.createChat(resolveAgentId(agentIdHeader), spec);
    }

    /**
     * Get detailed information about a specific chat by UUID.
     * GET /chats/{chat_id}
     */
    @GetMapping("/{chat_id}")
    public ChatHistory getChat(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader,
            @PathVariable("chat_id") String chatId) {
        ChatHistory history = chatService.getChatHistory(resolveAgentId(agentIdHeader), chatId);
        if (history == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found: " + chatId);
        }
        return history;
    }

    /**
     * Update an existing chat.
     * PUT /chats/{chat_id}
     */
    @PutMapping("/{chat_id}")
    public ChatSpec updateChat(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader,
            @PathVariable("chat_id") String chatId,
            @RequestBody ChatUpdate update) {
        ChatSpec updated = chatService.updateChat(resolveAgentId(agentIdHeader), chatId, update);
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found: " + chatId);
        }
        return updated;
    }

    /**
     * Delete a chat by UUID.
     * DELETE /chats/{chat_id}
     */
    @DeleteMapping("/{chat_id}")
    public Map<String, Object> deleteChat(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader,
            @PathVariable("chat_id") String chatId) {
        boolean deleted = chatService.deleteChat(resolveAgentId(agentIdHeader), chatId);
        if (!deleted) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chat not found: " + chatId);
        }
        return Map.of("deleted", true);
    }

    /**
     * Delete chats by chat IDs.
     * POST /chats/batch-delete
     */
    @PostMapping("/batch-delete")
    public Map<String, Object> batchDeleteChats(
            @RequestHeader(value = "X-Agent-Id", required = false) String agentIdHeader,
            @RequestBody List<String> chatIds) {
        boolean deleted = chatService.deleteChats(resolveAgentId(agentIdHeader), chatIds);
        return Map.of("deleted", deleted);
    }
}
