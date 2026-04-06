package com.copaw.storage;

import com.copaw.model.chat.ChatHistory;
import com.copaw.model.chat.ChatSpec;
import com.copaw.model.chat.ChatsFile;
import com.copaw.model.chat.Message;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Store for managing chat records.
 * Reads and writes chat sessions and history.
 */
@Component
public class ChatStore {
    private static final Logger log = LoggerFactory.getLogger(ChatStore.class);

    private final CoPawDataDir dataDir;
    private final JsonFileStore<Object> jsonFileStore;

    public ChatStore(CoPawDataDir dataDir, JsonFileStore<Object> jsonFileStore) {
        this.dataDir = dataDir;
        this.jsonFileStore = jsonFileStore;
    }

    /**
     * Load chats file for an agent.
     *
     * @param agentId the agent ID
     * @return the chats file, or an empty one if not found
     */
    public ChatsFile loadChatsFile(String agentId) {
        Path chatsPath = dataDir.getChatsPath(agentId);
        if (!Files.exists(chatsPath)) {
            return ChatsFile.builder().build();
        }

        try {
            String content = Files.readString(chatsPath);
            // Try to parse as ChatsFile (new format)
            try {
                return jsonFileStore.getObjectMapper().readValue(content, ChatsFile.class);
            } catch (IOException e) {
                // Check if content is an old-format array (e.g., "[]" or "[{...}]")
                String trimmed = content.trim();
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    log.info("Detected old-format chats.json for agent {}, migrating to new format", agentId);
                    ChatsFile newFile = ChatsFile.builder().build();
                    // Save the new format back to file
                    saveChatsFile(agentId, newFile);
                    return newFile;
                }
                // If not an array, it's a different kind of error
                throw e;
            }
        } catch (IOException e) {
            log.warn("Failed to load chats for agent {}: {}", agentId, e.getMessage());
            return ChatsFile.builder().build();
        }
    }

    /**
     * Save chats file for an agent.
     *
     * @param agentId   the agent ID
     * @param chatsFile the chats file to save
     */
    public void saveChatsFile(String agentId, ChatsFile chatsFile) {
        Path chatsPath = dataDir.getChatsPath(agentId);
        try {
            Files.createDirectories(chatsPath.getParent());
            String json = jsonFileStore.getObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(chatsFile);
            Files.writeString(chatsPath, json);
        } catch (IOException e) {
            log.error("Failed to save chats for agent {}: {}", agentId, e.getMessage());
            throw new RuntimeException("Failed to save chats", e);
        }
    }

    /**
     * Get all chats for an agent.
     *
     * @param agentId the agent ID
     * @return list of chat specs
     */
    public List<ChatSpec> listChats(String agentId) {
        ChatsFile chatsFile = loadChatsFile(agentId);
        return chatsFile.getChats();
    }

    /**
     * Get a specific chat by ID.
     *
     * @param agentId the agent ID
     * @param chatId  the chat ID
     * @return the chat spec, or null if not found
     */
    public ChatSpec getChat(String agentId, String chatId) {
        List<ChatSpec> chats = listChats(agentId);
        return chats.stream()
                .filter(c -> c.getId().equals(chatId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Create a new chat.
     *
     * @param agentId the agent ID
     * @param chat    the chat to create
     */
    public void createChat(String agentId, ChatSpec chat) {
        ChatsFile chatsFile = loadChatsFile(agentId);
        chatsFile.getChats().add(chat);
        saveChatsFile(agentId, chatsFile);
    }

    /**
     * Update a chat.
     *
     * @param agentId the agent ID
     * @param chat    the chat to update
     */
    public void updateChat(String agentId, ChatSpec chat) {
        ChatsFile chatsFile = loadChatsFile(agentId);
        List<ChatSpec> chats = chatsFile.getChats();
        for (int i = 0; i < chats.size(); i++) {
            if (chats.get(i).getId().equals(chat.getId())) {
                chats.set(i, chat);
                break;
            }
        }
        saveChatsFile(agentId, chatsFile);
    }

    /**
     * Delete a chat.
     *
     * @param agentId the agent ID
     * @param chatId  the chat ID to delete
     */
    public void deleteChat(String agentId, String chatId) {
        ChatsFile chatsFile = loadChatsFile(agentId);
        chatsFile.getChats().removeIf(c -> c.getId().equals(chatId));
        saveChatsFile(agentId, chatsFile);
    }

    /**
     * Load chat history for a session.
     *
     * @param agentId   the agent ID
     * @param sessionId the session ID
     * @return the chat history
     */
    public ChatHistory loadChatHistory(String agentId, String sessionId) {
        Path sessionPath = dataDir.getSessionsDir(agentId).resolve(sessionId + ".json");
        if (!Files.exists(sessionPath)) {
            return ChatHistory.builder().build();
        }

        try {
            String content = Files.readString(sessionPath);
            return jsonFileStore.getObjectMapper().readValue(content, ChatHistory.class);
        } catch (IOException e) {
            log.warn("Failed to load chat history for session {}: {}", sessionId, e.getMessage());
            return ChatHistory.builder().build();
        }
    }

    /**
     * Save chat history for a session.
     *
     * @param agentId   the agent ID
     * @param sessionId the session ID
     * @param history   the chat history to save
     */
    public void saveChatHistory(String agentId, String sessionId, ChatHistory history) {
        Path sessionPath = dataDir.getSessionsDir(agentId).resolve(sessionId + ".json");
        try {
            Files.createDirectories(sessionPath.getParent());
            String json = jsonFileStore.getObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(history);
            Files.writeString(sessionPath, json);
        } catch (IOException e) {
            log.error("Failed to save chat history for session {}: {}", sessionId, e.getMessage());
            throw new RuntimeException("Failed to save chat history", e);
        }
    }

    /**
     * Append a message to a chat session.
     *
     * @param agentId   the agent ID
     * @param sessionId the session ID
     * @param message   the message to append
     */
    public void appendMessage(String agentId, String sessionId, Message message) {
        ChatHistory history = loadChatHistory(agentId, sessionId);
        history.getMessages().add(message);
        saveChatHistory(agentId, sessionId, history);
    }
}
