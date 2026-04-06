package cn.sangshy.sa.model.console;

import java.time.Instant;
import java.util.*;

/**
 * Helper class to build SSE event payloads compatible with the AgentScope Runtime protocol.
 *
 * The frontend {@code @agentscope-ai/chat} library expects three object types:
 * <ul>
 *   <li>{@code object: "message"} – creates or updates a message in the output list</li>
 *   <li>{@code object: "content"} – streams delta content into an existing message</li>
 *   <li>{@code object: "response"} – marks the overall response as completed/failed</li>
 * </ul>
 */
public final class SSEEvent {

    private SSEEvent() { /* utility class */ }

    // -----------------------------------------------------------------------
    // message object helpers
    // -----------------------------------------------------------------------

    /**
     * Create a new runtime message with initial delta content.
     *
     * @param msgId   stable message id (reuse across chunks of the same logical message)
     * @param msgType "message", "reasoning", "plugin_call", "plugin_call_output", etc.
     * @param text    initial text content
     * @return a Map representing the JSON payload
     */
    public static Map<String, Object> newMessage(String msgId, String msgType, String text) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("object", "content");
        content.put("type", "text");
        content.put("text", text != null ? text : "");
        content.put("delta", true);
        content.put("status", "in_progress");

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("object", "message");
        msg.put("id", msgId);
        msg.put("type", msgType);
        msg.put("role", "assistant");
        msg.put("content", List.of(content));
        msg.put("status", "in_progress");
        return msg;
    }

    /**
     * Create a delta content chunk that appends text to an existing message.
     */
    public static Map<String, Object> contentDelta(String msgId, String text) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("object", "content");
        c.put("type", "text");
        c.put("text", text != null ? text : "");
        c.put("delta", true);
        c.put("msg_id", msgId);
        c.put("status", "in_progress");
        return c;
    }

    /**
     * Mark an existing message as completed with full accumulated content.
     *
     * @param msgId           stable message id
     * @param msgType         "message", "reasoning", etc.
     * @param accumulatedText the full text accumulated during streaming
     * @return a Map representing the completed message payload
     */
    public static Map<String, Object> messageCompleted(String msgId, String msgType, String accumulatedText) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("object", "content");
        content.put("type", "text");
        content.put("text", accumulatedText != null ? accumulatedText : "");
        content.put("delta", false);
        content.put("status", "completed");

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("object", "message");
        msg.put("id", msgId);
        msg.put("type", msgType);
        msg.put("role", "assistant");
        msg.put("content", List.of(content));
        msg.put("status", "completed");
        return msg;
    }

    // -----------------------------------------------------------------------
    // tool call / tool result helpers
    // -----------------------------------------------------------------------

    /**
     * Create a plugin_call message (tool invocation).
     */
    public static Map<String, Object> toolCallMessage(String msgId, String callId, String name, String arguments) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("call_id", callId);
        data.put("name", name);
        data.put("arguments", arguments);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("object", "content");
        content.put("type", "data");
        content.put("data", data);
        content.put("delta", false);
        content.put("status", "in_progress");

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("object", "message");
        msg.put("id", msgId);
        msg.put("type", "plugin_call");
        msg.put("role", "assistant");
        msg.put("content", List.of(content));
        msg.put("status", "in_progress");
        return msg;
    }

    /**
     * Create a plugin_call_output message (tool result).
     */
    public static Map<String, Object> toolResultMessage(String msgId, String callId, String name, String output) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("call_id", callId);
        data.put("name", name);
        data.put("output", output);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("object", "content");
        content.put("type", "data");
        content.put("data", data);
        content.put("delta", false);
        content.put("status", "completed");

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("object", "message");
        msg.put("id", msgId);
        msg.put("type", "plugin_call_output");
        msg.put("role", "assistant");
        msg.put("content", List.of(content));
        msg.put("status", "completed");
        return msg;
    }

    // -----------------------------------------------------------------------
    // response object helpers
    // -----------------------------------------------------------------------

    /**
     * Create a response-completed event that tells the frontend the stream is done.
     * The output list MUST contain all completed messages with their full content,
     * matching the Python AgentScope Runtime protocol.
     *
     * @param completedMessages list of completed message Maps (from {@link #messageCompleted})
     * @return a Map representing the response-completed payload
     */
    public static Map<String, Object> responseCompleted(List<Map<String, Object>> completedMessages) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("object", "response");
        r.put("id", "response_" + UUID.randomUUID());
        r.put("status", "completed");
        r.put("created_at", Instant.now().getEpochSecond());
        r.put("output", completedMessages != null ? completedMessages : List.of());
        return r;
    }

    /**
     * Create a response-failed event for fatal errors.
     */
    public static Map<String, Object> responseFailed(String errorMessage) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "stream_error");
        error.put("message", errorMessage != null ? errorMessage : "Unknown error");

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("object", "response");
        r.put("id", "response_" + UUID.randomUUID());
        r.put("status", "failed");
        r.put("created_at", Instant.now().getEpochSecond());
        r.put("output", List.of());
        r.put("error", error);
        return r;
    }
}
