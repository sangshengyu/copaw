package com.copaw.model.console;

/**
 * SSE event types for streaming responses.
 * Must match Python version for frontend compatibility.
 */
public enum SSEEventType {
    /**
     * Thinking/reasoning content
     */
    THINKING("thinking"),
    
    /**
     * Tool call initiated
     */
    TOOL_CALL("tool_call"),
    
    /**
     * Tool execution result
     */
    TOOL_RESULT("tool_result"),
    
    /**
     * Text content chunk
     */
    TEXT("text"),
    
    /**
     * Stream completed
     */
    DONE("done"),
    
    /**
     * Error occurred
     */
    ERROR("error");

    private final String value;

    SSEEventType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static SSEEventType fromValue(String value) {
        for (SSEEventType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown SSE event type: " + value);
    }
}
