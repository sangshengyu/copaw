package com.copaw.model.console;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SSEEvent protocol compatibility.
 * Verifies that SSE events conform to AgentScope Runtime protocol.
 */
@DisplayName("SSEEvent Protocol Tests")
class SSEEventTest {

    @Test
    @DisplayName("newMessage should create message with correct structure")
    void testNewMessageStructure() {
        // Given
        String msgId = "msg_test_123";
        String msgType = "message";
        String text = "Hello World";

        // When
        Map<String, Object> event = SSEEvent.newMessage(msgId, msgType, text);

        // Then
        assertEquals("message", event.get("object"));
        assertEquals(msgId, event.get("id"));
        assertEquals(msgType, event.get("type"));
        assertEquals("assistant", event.get("role"));
        assertEquals("in_progress", event.get("status"));
        
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) event.get("content");
        assertNotNull(content);
        assertEquals(1, content.size());
        
        Map<String, Object> contentItem = content.get(0);
        assertEquals("content", contentItem.get("object"));
        assertEquals("text", contentItem.get("type"));
        assertEquals(text, contentItem.get("text"));
        assertEquals(true, contentItem.get("delta"));
        assertEquals("in_progress", contentItem.get("status"));
    }

    @Test
    @DisplayName("contentDelta should create delta content chunk")
    void testContentDeltaStructure() {
        // Given
        String msgId = "msg_test_456";
        String text = " streaming text";

        // When
        Map<String, Object> event = SSEEvent.contentDelta(msgId, text);

        // Then
        assertEquals("content", event.get("object"));
        assertEquals("text", event.get("type"));
        assertEquals(text, event.get("text"));
        assertEquals(true, event.get("delta"));
        assertEquals(msgId, event.get("msg_id"));
        assertEquals("in_progress", event.get("status"));
    }

    @Test
    @DisplayName("messageCompleted should mark message as completed")
    void testMessageCompletedStructure() {
        // Given
        String msgId = "msg_test_789";
        String msgType = "reasoning";
        String accumulatedText = "Full reasoning content";

        // When
        Map<String, Object> event = SSEEvent.messageCompleted(msgId, msgType, accumulatedText);

        // Then
        assertEquals("message", event.get("object"));
        assertEquals(msgId, event.get("id"));
        assertEquals(msgType, event.get("type"));
        assertEquals("assistant", event.get("role"));
        assertEquals("completed", event.get("status"));
        
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) event.get("content");
        assertNotNull(content);
        assertEquals(1, content.size());
        
        Map<String, Object> contentItem = content.get(0);
        assertEquals("content", contentItem.get("object"));
        assertEquals("text", contentItem.get("type"));
        assertEquals(accumulatedText, contentItem.get("text"));
        assertEquals(false, contentItem.get("delta"));
        assertEquals("completed", contentItem.get("status"));
    }

    @Test
    @DisplayName("toolCallMessage should create plugin_call message")
    void testToolCallMessageStructure() {
        // Given
        String msgId = "msg_tool_123";
        String callId = "call_456";
        String name = "read_file";
        String arguments = "{\"path\": \"/test.txt\"}";

        // When
        Map<String, Object> event = SSEEvent.toolCallMessage(msgId, callId, name, arguments);

        // Then
        assertEquals("message", event.get("object"));
        assertEquals(msgId, event.get("id"));
        assertEquals("plugin_call", event.get("type"));
        assertEquals("assistant", event.get("role"));
        assertEquals("in_progress", event.get("status"));
        
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) event.get("content");
        assertNotNull(content);
        assertEquals(1, content.size());
        
        Map<String, Object> contentItem = content.get(0);
        assertEquals("content", contentItem.get("object"));
        assertEquals("data", contentItem.get("type"));
        assertEquals(false, contentItem.get("delta"));
        assertEquals("in_progress", contentItem.get("status"));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) contentItem.get("data");
        assertNotNull(data);
        assertEquals(callId, data.get("call_id"));
        assertEquals(name, data.get("name"));
        assertEquals(arguments, data.get("arguments"));
    }

    @Test
    @DisplayName("toolResultMessage should create plugin_call_output message")
    void testToolResultMessageStructure() {
        // Given
        String msgId = "msg_result_123";
        String callId = "call_456";
        String name = "read_file";
        String output = "File content here";

        // When
        Map<String, Object> event = SSEEvent.toolResultMessage(msgId, callId, name, output);

        // Then
        assertEquals("message", event.get("object"));
        assertEquals(msgId, event.get("id"));
        assertEquals("plugin_call_output", event.get("type"));
        assertEquals("assistant", event.get("role"));
        assertEquals("completed", event.get("status"));
        
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) event.get("content");
        assertNotNull(content);
        assertEquals(1, content.size());
        
        Map<String, Object> contentItem = content.get(0);
        assertEquals("content", contentItem.get("object"));
        assertEquals("data", contentItem.get("type"));
        assertEquals(false, contentItem.get("delta"));
        assertEquals("completed", contentItem.get("status"));
        
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) contentItem.get("data");
        assertNotNull(data);
        assertEquals(callId, data.get("call_id"));
        assertEquals(name, data.get("name"));
        assertEquals(output, data.get("output"));
    }

    @Test
    @DisplayName("responseCompleted should create response with all completed messages")
    void testResponseCompletedStructure() {
        // Given
        Map<String, Object> msg1 = SSEEvent.messageCompleted("msg_1", "reasoning", "Reasoning text");
        Map<String, Object> msg2 = SSEEvent.messageCompleted("msg_2", "message", "Response text");
        List<Map<String, Object>> completedMessages = List.of(msg1, msg2);

        // When
        Map<String, Object> event = SSEEvent.responseCompleted(completedMessages);

        // Then
        assertEquals("response", event.get("object"));
        assertNotNull(event.get("id"));
        assertTrue(((String) event.get("id")).startsWith("response_"));
        assertEquals("completed", event.get("status"));
        assertNotNull(event.get("created_at"));
        
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> output = (List<Map<String, Object>>) event.get("output");
        assertNotNull(output);
        assertEquals(2, output.size());
        assertEquals(msg1, output.get(0));
        assertEquals(msg2, output.get(1));
    }

    @Test
    @DisplayName("responseFailed should create error response")
    void testResponseFailedStructure() {
        // Given
        String errorMessage = "Connection timeout";

        // When
        Map<String, Object> event = SSEEvent.responseFailed(errorMessage);

        // Then
        assertEquals("response", event.get("object"));
        assertNotNull(event.get("id"));
        assertEquals("failed", event.get("status"));
        assertNotNull(event.get("created_at"));
        
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> output = (List<Map<String, Object>>) event.get("output");
        assertNotNull(output);
        assertTrue(output.isEmpty());
        
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) event.get("error");
        assertNotNull(error);
        assertEquals("stream_error", error.get("code"));
        assertEquals(errorMessage, error.get("message"));
    }

    @Test
    @DisplayName("responseCompleted should handle empty message list")
    void testResponseCompletedWithEmptyList() {
        // When
        Map<String, Object> event = SSEEvent.responseCompleted(List.of());

        // Then
        assertEquals("response", event.get("object"));
        assertEquals("completed", event.get("status"));
        
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> output = (List<Map<String, Object>>) event.get("output");
        assertNotNull(output);
        assertTrue(output.isEmpty());
    }

    @Test
    @DisplayName("responseCompleted should handle null message list")
    void testResponseCompletedWithNullList() {
        // When
        Map<String, Object> event = SSEEvent.responseCompleted(null);

        // Then
        assertEquals("response", event.get("object"));
        assertEquals("completed", event.get("status"));
        
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> output = (List<Map<String, Object>>) event.get("output");
        assertNotNull(output);
        assertTrue(output.isEmpty());
    }

    @Test
    @DisplayName("Events should maintain insertion order with LinkedHashMap")
    void testEventOrderPreservation() {
        // Given
        Map<String, Object> event = SSEEvent.newMessage("msg_1", "message", "test");

        // When - convert to array to check order
        Object[] keys = event.keySet().toArray();

        // Then - verify key order matches insertion order
        assertEquals("object", keys[0]);
        assertEquals("id", keys[1]);
        assertEquals("type", keys[2]);
        assertEquals("role", keys[3]);
        assertEquals("content", keys[4]);
        assertEquals("status", keys[5]);
    }

    @Test
    @DisplayName("Content should handle null text gracefully")
    void testNullTextHandling() {
        // When
        Map<String, Object> event = SSEEvent.newMessage("msg_1", "message", null);

        // Then
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) event.get("content");
        Map<String, Object> contentItem = content.get(0);
        assertEquals("", contentItem.get("text"));
    }
}
