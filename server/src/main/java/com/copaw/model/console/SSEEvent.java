package com.copaw.model.console;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * SSE event payload structure.
 * Must match Python version for frontend compatibility.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SSEEvent {
    
    /**
     * Event type
     */
    @JsonProperty("type")
    private String type;
    
    /**
     * Text content for thinking/text events
     */
    @JsonProperty("text")
    private String text;
    
    /**
     * Tool call ID
     */
    @JsonProperty("tool_call_id")
    private String toolCallId;
    
    /**
     * Tool name
     */
    @JsonProperty("name")
    private String name;
    
    /**
     * Tool input arguments
     */
    @JsonProperty("input")
    private Map<String, Object> input;
    
    /**
     * Tool output/result
     */
    @JsonProperty("output")
    private Object output;
    
    /**
     * Content blocks (for complex responses)
     */
    @JsonProperty("content")
    private List<Map<String, Object>> content;
    
    /**
     * Whether this is the last event in the stream
     */
    @JsonProperty("last")
    private Boolean last;
    
    /**
     * Error message (for error events)
     */
    @JsonProperty("error")
    private String error;
    
    /**
     * Session ID
     */
    @JsonProperty("session_id")
    private String sessionId;
    
    /**
     * Agent ID
     */
    @JsonProperty("agent_id")
    private String agentId;
    
    /**
     * Create a thinking event
     */
    public static SSEEvent thinking(String text, boolean last) {
        return SSEEvent.builder()
                .type(SSEEventType.THINKING.getValue())
                .text(text)
                .last(last)
                .build();
    }
    
    /**
     * Create a text event
     */
    public static SSEEvent text(String text, boolean last) {
        return SSEEvent.builder()
                .type(SSEEventType.TEXT.getValue())
                .text(text)
                .last(last)
                .build();
    }
    
    /**
     * Create a tool call event
     */
    public static SSEEvent toolCall(String toolCallId, String name, Map<String, Object> input) {
        return SSEEvent.builder()
                .type(SSEEventType.TOOL_CALL.getValue())
                .toolCallId(toolCallId)
                .name(name)
                .input(input)
                .build();
    }
    
    /**
     * Create a tool result event
     */
    public static SSEEvent toolResult(String toolCallId, Object output) {
        return SSEEvent.builder()
                .type(SSEEventType.TOOL_RESULT.getValue())
                .toolCallId(toolCallId)
                .output(output)
                .build();
    }
    
    /**
     * Create a done event
     */
    public static SSEEvent done() {
        return SSEEvent.builder()
                .type(SSEEventType.DONE.getValue())
                .last(true)
                .build();
    }
    
    /**
     * Create an error event
     */
    public static SSEEvent error(String message) {
        return SSEEvent.builder()
                .type(SSEEventType.ERROR.getValue())
                .error(message)
                .last(true)
                .build();
    }
}
