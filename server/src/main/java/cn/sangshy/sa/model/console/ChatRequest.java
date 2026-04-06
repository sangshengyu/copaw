package cn.sangshy.sa.model.console;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Chat request for console endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatRequest {
    
    /**
     * Chat/session ID
     */
    @JsonProperty("chat_id")
    private String chatId;
    
    /**
     * Agent ID
     */
    @JsonProperty("agent_id")
    private String agentId;
    
    /**
     * User message content
     */
    @JsonProperty("content")
    private String content;
    
    /**
     * Message content blocks (for multimodal)
     */
    @JsonProperty("content_blocks")
    private List<Map<String, Object>> contentBlocks;
    
    /**
     * File attachments
     */
    @JsonProperty("attachments")
    private List<Attachment> attachments;
    
    /**
     * Session ID for conversation continuity
     */
    @JsonProperty("session_id")
    private String sessionId;
    
    /**
     * User ID
     */
    @JsonProperty("user_id")
    private String userId;
    
    /**
     * Channel name
     */
    @JsonProperty("channel")
    private String channel;
    
    /**
     * Additional metadata
     */
    @JsonProperty("meta")
    private Map<String, Object> meta;
    
    /**
     * Input messages array (for @agentscope-ai/chat format)
     * Each item has: role, content (string or array of blocks)
     */
    @JsonProperty("input")
    private List<Map<String, Object>> input;
    
    /**
     * Whether to stream the response
     */
    @JsonProperty("stream")
    private Boolean stream;
    
    /**
     * File attachment info
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Attachment {
        @JsonProperty("url")
        private String url;
        
        @JsonProperty("file_name")
        private String fileName;
        
        @JsonProperty("stored_name")
        private String storedName;
        
        @JsonProperty("mime_type")
        private String mimeType;
        
        @JsonProperty("size")
        private Long size;
    }
}
