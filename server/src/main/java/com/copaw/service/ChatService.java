package com.copaw.service;

import com.copaw.model.chat.ChatHistory;
import com.copaw.model.chat.ChatSpec;
import com.copaw.model.chat.ChatUpdate;
import com.copaw.service.AgentService;
import com.copaw.storage.ChatStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service for managing chats.
 */
@Service
public class ChatService {

    private final ChatStore chatStore;
    private final AgentService agentService;

    public ChatService(ChatStore chatStore, AgentService agentService) {
        this.chatStore = chatStore;
        this.agentService = agentService;
    }

    /**
     * Get the active agent ID.
     */
    private String getActiveAgentId() {
        return agentService.getActiveAgentId();
    }

    /**
     * List all chats for the active agent.
     *
     * @param userId  optional user ID filter
     * @param channel optional channel filter
     * @return list of chat specs
     */
    public List<ChatSpec> listChats(String userId, String channel) {
        String agentId = getActiveAgentId();
        List<ChatSpec> chats = chatStore.listChats(agentId);

        // Apply filters
        return chats.stream()
                .filter(c -> userId == null || userId.equals(c.getUserId()))
                .filter(c -> channel == null || channel.equals(c.getChannel()))
                .collect(Collectors.toList());
    }

    /**
     * Get a specific chat by ID.
     *
     * @param chatId the chat ID
     * @return the chat spec, or null if not found
     */
    public ChatSpec getChat(String chatId) {
        String agentId = getActiveAgentId();
        return chatStore.getChat(agentId, chatId);
    }

    /**
     * Create a new chat.
     *
     * @param spec the chat spec
     * @return the created chat with generated ID
     */
    public ChatSpec createChat(ChatSpec spec) {
        String agentId = getActiveAgentId();

        // Generate UUID for new chat
        String chatId = UUID.randomUUID().toString();

        // Set timestamps
        Instant now = Instant.now();

        ChatSpec newChat = ChatSpec.builder()
                .id(chatId)
                .name(spec.getName() != null ? spec.getName() : "New Chat")
                .sessionId(spec.getSessionId())
                .userId(spec.getUserId())
                .channel(spec.getChannel() != null ? spec.getChannel() : "console")
                .meta(spec.getMeta())
                .createdAt(now)
                .updatedAt(now)
                .status("idle")
                .build();

        chatStore.createChat(agentId, newChat);
        return newChat;
    }

    /**
     * Update a chat.
     *
     * @param chatId    the chat ID
     * @param update    the update data
     * @return the updated chat, or null if not found
     */
    public ChatSpec updateChat(String chatId, ChatUpdate update) {
        String agentId = getActiveAgentId();
        ChatSpec existing = chatStore.getChat(agentId, chatId);

        if (existing == null) {
            return null;
        }

        // Apply updates
        if (update.getName() != null) {
            existing.setName(update.getName());
        }
        existing.setUpdatedAt(Instant.now());

        chatStore.updateChat(agentId, existing);
        return existing;
    }

    /**
     * Delete a chat.
     *
     * @param chatId the chat ID
     * @return true if deleted
     */
    public boolean deleteChat(String chatId) {
        String agentId = getActiveAgentId();
        ChatSpec existing = chatStore.getChat(agentId, chatId);

        if (existing == null) {
            return false;
        }

        chatStore.deleteChat(agentId, chatId);
        return true;
    }

    /**
     * Delete multiple chats.
     *
     * @param chatIds the chat IDs to delete
     * @return true if all deleted
     */
    public boolean deleteChats(List<String> chatIds) {
        String agentId = getActiveAgentId();
        boolean allDeleted = true;

        for (String chatId : chatIds) {
            ChatSpec existing = chatStore.getChat(agentId, chatId);
            if (existing != null) {
                chatStore.deleteChat(agentId, chatId);
            } else {
                allDeleted = false;
            }
        }

        return allDeleted;
    }

    /**
     * Get chat history.
     *
     * @param chatId the chat ID
     * @return the chat history
     */
    public ChatHistory getChatHistory(String chatId) {
        String agentId = getActiveAgentId();
        ChatSpec chat = chatStore.getChat(agentId, chatId);

        if (chat == null) {
            return null;
        }

        if (chat.getSessionId() != null) {
            ChatHistory history = chatStore.loadChatHistory(agentId, chat.getSessionId());
            history.setStatus(chat.getStatus() != null ? chat.getStatus() : "idle");
            return history;
        }

        return ChatHistory.builder()
                .status(chat.getStatus() != null ? chat.getStatus() : "idle")
                .build();
    }
}
