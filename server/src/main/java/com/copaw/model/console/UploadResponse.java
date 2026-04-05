package com.copaw.model.console;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * File upload response.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadResponse {
    
    /**
     * URL to access the file
     */
    @JsonProperty("url")
    private String url;
    
    /**
     * Original file name
     */
    @JsonProperty("file_name")
    private String fileName;
    
    /**
     * Stored file name (UUID based)
     */
    @JsonProperty("stored_name")
    private String storedName;
    
    /**
     * File size in bytes
     */
    @JsonProperty("size")
    private Long size;
    
    /**
     * MIME type
     */
    @JsonProperty("mime_type")
    private String mimeType;
}
