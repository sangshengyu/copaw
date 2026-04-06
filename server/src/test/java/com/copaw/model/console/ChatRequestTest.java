package com.copaw.model.console;

import com.copaw.model.chat.ChatHistory;
import com.copaw.model.chat.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ChatRequest deserialization.
 * Tests various input formats from frontend and API clients.
 */
@DisplayName("ChatRequest Model Tests")
class ChatRequestTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("Should deserialize basic chat request")
    void testBasicChatRequest() throws Exception {
        // Given
        String json = """
                {
                    "chat_id": "chat_123",
                    "agent_id": "agent_456",
                    "content": "Hello World"
                }
                """;

        // When
        ChatRequest request = objectMapper.readValue(json, ChatRequest.class);

        // Then
        assertEquals("chat_123", request.getChatId());
        assertEquals("agent_456", request.getAgentId());
        assertEquals("Hello World", request.getContent());
        assertNull(request.getInput());
        assertNull(request.getSessionId());
    }

    @Test
    @DisplayName("Should deserialize request with input array format")
    void testInputArrayFormat() throws Exception {
        // Given
        String json = """
                {
                    "chat_id": "chat_789",
                    "input": [
                        {"role": "user", "content": "User message"},
                        {"role": "assistant", "content": "Assistant reply"}
                    ]
                }
                """;

        // When
        ChatRequest request = objectMapper.readValue(json, ChatRequest.class);

        // Then
        assertEquals("chat_789", request.getChatId());
        assertNotNull(request.getInput());
        assertEquals(2, request.getInput().size());
        
        Map<String, Object> userMsg = request.getInput().get(0);
        assertEquals("user", userMsg.get("role"));
        assertEquals("User message", userMsg.get("content"));
        
        Map<String, Object> assistantMsg = request.getInput().get(1);
        assertEquals("assistant", assistantMsg.get("role"));
        assertEquals("Assistant reply", assistantMsg.get("content"));
    }

    @Test
    @DisplayName("Should deserialize request with multimodal content blocks")
    void testMultimodalContentBlocks() throws Exception {
        // Given
        String json = """
                {
                    "chat_id": "chat_multimodal",
                    "input": [
                        {
                            "role": "user",
                            "content": [
                                {"type": "text", "text": "What's in this image?"},
                                {"type": "image", "url": "https://example.com/image.jpg"}
                            ]
                        }
                    ]
                }
                """;

        // When
        ChatRequest request = objectMapper.readValue(json, ChatRequest.class);

        // Then
        assertNotNull(request.getInput());
        assertEquals(1, request.getInput().size());
        
        Map<String, Object> userMsg = request.getInput().get(0);
        assertEquals("user", userMsg.get("role"));
        
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) userMsg.get("content");
        assertNotNull(content);
        assertEquals(2, content.size());
        
        Map<String, Object> textBlock = content.get(0);
        assertEquals("text", textBlock.get("type"));
        assertEquals("What's in this image?", textBlock.get("text"));
        
        Map<String, Object> imageBlock = content.get(1);
        assertEquals("image", imageBlock.get("type"));
        assertEquals("https://example.com/image.jpg", imageBlock.get("url"));
    }

    @Test
    @DisplayName("Should deserialize request with attachments")
    void testRequestWithAttachments() throws Exception {
        // Given
        String json = """
                {
                    "chat_id": "chat_attach",
                    "content": "Process this file",
                    "attachments": [
                        {
                            "url": "/files/test.pdf",
                            "file_name": "test.pdf",
                            "stored_name": "uuid_123.pdf",
                            "mime_type": "application/pdf",
                            "size": 102400
                        }
                    ]
                }
                """;

        // When
        ChatRequest request = objectMapper.readValue(json, ChatRequest.class);

        // Then
        assertEquals("chat_attach", request.getChatId());
        assertEquals("Process this file", request.getContent());
        assertNotNull(request.getAttachments());
        assertEquals(1, request.getAttachments().size());
        
        ChatRequest.Attachment attachment = request.getAttachments().get(0);
        assertEquals("/files/test.pdf", attachment.getUrl());
        assertEquals("test.pdf", attachment.getFileName());
        assertEquals("uuid_123.pdf", attachment.getStoredName());
        assertEquals("application/pdf", attachment.getMimeType());
        assertEquals(102400L, attachment.getSize());
    }

    @Test
    @DisplayName("Should deserialize request with session and user info")
    void testRequestWithSessionInfo() throws Exception {
        // Given
        String json = """
                {
                    "chat_id": "chat_session",
                    "session_id": "session_123",
                    "user_id": "user_456",
                    "channel": "web",
                    "meta": {
                        "browser": "Chrome",
                        "os": "macOS"
                    }
                }
                """;

        // When
        ChatRequest request = objectMapper.readValue(json, ChatRequest.class);

        // Then
        assertEquals("chat_session", request.getChatId());
        assertEquals("session_123", request.getSessionId());
        assertEquals("user_456", request.getUserId());
        assertEquals("web", request.getChannel());
        assertNotNull(request.getMeta());
        assertEquals("Chrome", request.getMeta().get("browser"));
        assertEquals("macOS", request.getMeta().get("os"));
    }

    @Test
    @DisplayName("Should deserialize request with stream flag")
    void testRequestWithStreamFlag() throws Exception {
        // Given
        String json = """
                {
                    "chat_id": "chat_stream",
                    "content": "Test",
                    "stream": true
                }
                """;

        // When
        ChatRequest request = objectMapper.readValue(json, ChatRequest.class);

        // Then
        assertEquals("chat_stream", request.getChatId());
        assertEquals("Test", request.getContent());
        assertEquals(true, request.getStream());
    }

    @Test
    @DisplayName("Should handle unknown fields gracefully")
    void testUnknownFieldsIgnored() throws Exception {
        // Given
        String json = """
                {
                    "chat_id": "chat_123",
                    "content": "Test",
                    "unknown_field": "should be ignored",
                    "another_unknown": 123
                }
                """;

        // When
        ChatRequest request = objectMapper.readValue(json, ChatRequest.class);

        // Then
        assertEquals("chat_123", request.getChatId());
        assertEquals("Test", request.getContent());
        // Should not throw exception for unknown fields
    }

    @Test
    @DisplayName("Should deserialize request with null chat_id")
    void testNullChatId() throws Exception {
        // Given
        String json = """
                {
                    "content": "Test message"
                }
                """;

        // When
        ChatRequest request = objectMapper.readValue(json, ChatRequest.class);

        // Then
        assertNull(request.getChatId());
        assertEquals("Test message", request.getContent());
    }

    @Test
    @DisplayName("Should serialize ChatRequest back to JSON")
    void testSerialization() throws Exception {
        // Given
        ChatRequest request = ChatRequest.builder()
                .chatId("chat_123")
                .agentId("agent_456")
                .content("Hello")
                .stream(true)
                .build();

        // When
        String json = objectMapper.writeValueAsString(request);

        // Then
        assertNotNull(json);
        assertTrue(json.contains("\"chat_id\":\"chat_123\""));
        assertTrue(json.contains("\"agent_id\":\"agent_456\""));
        assertTrue(json.contains("\"content\":\"Hello\""));
        assertTrue(json.contains("\"stream\":true"));
    }

    @Test
    @DisplayName("Attachment should serialize correctly")
    void testAttachmentSerialization() throws Exception {
        // Given
        ChatRequest.Attachment attachment = ChatRequest.Attachment.builder()
                .url("/files/test.pdf")
                .fileName("test.pdf")
                .storedName("uuid.pdf")
                .mimeType("application/pdf")
                .size(1024L)
                .build();

        ChatRequest request = ChatRequest.builder()
                .chatId("chat_123")
                .content("Process")
                .attachments(List.of(attachment))
                .build();

        // When
        String json = objectMapper.writeValueAsString(request);

        // Then
        assertNotNull(json);
        assertTrue(json.contains("\"attachments\""));
        assertTrue(json.contains("\"file_name\":\"test.pdf\""));
        assertTrue(json.contains("\"stored_name\":\"uuid.pdf\""));
        assertTrue(json.contains("\"mime_type\":\"application/pdf\""));
        assertTrue(json.contains("\"size\":1024"));
    }

    @Test
    @DisplayName("Message serialization should flatten additionalFields via @JsonAnyGetter")
    void testMessageSerializationFlattensAdditionalFields() throws Exception {
        // Given: a message with type in additionalFields
        Message msg = Message.builder()
                .role("assistant")
                .content("Hello world")
                .build();
        msg.setAdditionalField("type", "message");

        // When
        String json = objectMapper.writeValueAsString(msg);

        // Then: type should be at top level, additionalFields should NOT appear
        assertTrue(json.contains("\"type\":\"message\""), "type should be flattened to top level");
        assertFalse(json.contains("additionalFields"), "additionalFields should NOT appear in JSON output");
        assertTrue(json.contains("\"role\":\"assistant\""));
        assertTrue(json.contains("\"content\":\"Hello world\""));
    }

    @Test
    @DisplayName("Message deserialization should collect unknown fields into additionalFields")
    void testMessageDeserializationCollectsUnknownFields() throws Exception {
        // Given: JSON with extra 'type' field
        String json = "{\"role\":\"user\",\"content\":\"hi\",\"type\":\"reasoning\"}";

        // When
        Message msg = objectMapper.readValue(json, Message.class);

        // Then
        assertEquals("user", msg.getRole());
        assertEquals("hi", msg.getContent());
        assertEquals("reasoning", msg.getAdditionalFields().get("type"));
    }

    @Test
    @DisplayName("Message roundtrip should preserve type field without additionalFields key")
    void testMessageRoundtripPreservesType() throws Exception {
        // Given: JSON with type field
        String input = "{\"role\":\"assistant\",\"content\":\"test\",\"type\":\"message\"}";

        // When: deserialize then serialize
        Message msg = objectMapper.readValue(input, Message.class);
        String output = objectMapper.writeValueAsString(msg);

        // Then: roundtrip should preserve type and not add additionalFields
        assertTrue(output.contains("\"type\":\"message\""));
        assertFalse(output.contains("additionalFields"));
    }

    @Test
    @DisplayName("ChatHistory with typed messages should serialize correctly")
    void testChatHistoryWithTypedMessages() throws Exception {
        // Given: a chat history like the one saved by ConsoleController
        String historyJson = "{\"messages\":[" +
                "{\"role\":\"user\",\"content\":\"hello\",\"type\":\"message\"}," +
                "{\"role\":\"assistant\",\"content\":\"thinking...\",\"type\":\"reasoning\"}," +
                "{\"role\":\"assistant\",\"content\":\"Hi!\",\"type\":\"message\"}" +
                "],\"status\":\"idle\"}";

        // When: deserialize and re-serialize (simulating API load → HTTP response)
        ChatHistory history = objectMapper.readValue(historyJson, ChatHistory.class);
        String output = objectMapper.writeValueAsString(history);

        // Then: output should have type fields but NO additionalFields
        assertFalse(output.contains("additionalFields"), "API response must not contain additionalFields");
        assertEquals(3, history.getMessages().size());
        // Verify type is preserved in roundtrip
        assertTrue(output.contains("\"type\":\"message\""));
        assertTrue(output.contains("\"type\":\"reasoning\""));
    }
}
