package cn.sangshy.sa.controller;

import cn.sangshy.sa.service.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Controller for workspace export/import operations.
 */
@RestController
@RequestMapping("/workspace")
public class WorkspaceController {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceController.class);

    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    /**
     * Download workspace as ZIP file.
     */
    @GetMapping("/download")
    public ResponseEntity<byte[]> downloadWorkspace(@RequestParam("agent_id") String agentId) {
        try {
            byte[] zipData = workspaceService.exportWorkspace(agentId);

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String filename = String.format("sa_workspace_%s_%s.zip", agentId, timestamp);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDispositionFormData("attachment", filename);
            headers.setContentLength(zipData.length);

            return new ResponseEntity<>(zipData, headers, HttpStatus.OK);

        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IOException e) {
            log.error("Failed to export workspace: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to export workspace: " + e.getMessage());
        }
    }

    /**
     * Upload and import workspace from ZIP file.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Boolean> uploadWorkspace(@RequestParam("agent_id") String agentId,
                                                 @RequestParam("file") MultipartFile file,
                                                 @RequestParam(value = "merge", defaultValue = "true") boolean merge) {
        // Validate content type
        String contentType = file.getContentType();
        if (contentType != null && !contentType.equals("application/zip")
                && !contentType.equals("application/x-zip-compressed")
                && !contentType.equals("application/octet-stream")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Expected a zip file, got content-type: " + contentType);
        }

        try {
            byte[] zipData = file.getBytes();

            // Validate ZIP
            if (!workspaceService.validateZip(zipData)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Uploaded file is not a valid zip archive");
            }

            // Import workspace
            workspaceService.importWorkspace(agentId, zipData, merge);

            return Map.of("success", true);

        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException e) {
            log.error("Failed to import workspace: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to import workspace: " + e.getMessage());
        }
    }
}
