package cn.sangshy.sa.service;

import cn.sangshy.sa.storage.SADataDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Service for workspace export/import operations.
 */
@Service
public class WorkspaceService {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);

    private final SADataDir dataDir;

    public WorkspaceService(SADataDir dataDir) {
        this.dataDir = dataDir;
    }

    /**
     * Export workspace as ZIP bytes.
     *
     * @param agentId the agent ID
     * @return ZIP file bytes
     */
    public byte[] exportWorkspace(String agentId) throws IOException {
        Path workspaceDir = dataDir.getAgentDir(agentId);

        if (!Files.exists(workspaceDir)) {
            throw new IllegalArgumentException("Workspace not found: " + agentId);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            Files.walk(workspaceDir).forEach(path -> {
                try {
                    String zipEntryName = workspaceDir.relativize(path).toString();

                    if (Files.isDirectory(path)) {
                        // Add directory entry (ending with /)
                        if (!zipEntryName.isEmpty()) {
                            ZipEntry zipEntry = new ZipEntry(zipEntryName + "/");
                            zos.putNextEntry(zipEntry);
                            zos.closeEntry();
                        }
                    } else {
                        // Add file entry
                        ZipEntry zipEntry = new ZipEntry(zipEntryName);
                        zos.putNextEntry(zipEntry);
                        Files.copy(path, zos);
                        zos.closeEntry();
                    }
                } catch (IOException e) {
                    log.warn("Failed to add {} to zip: {}", path, e.getMessage());
                }
            });
        }

        return baos.toByteArray();
    }

    /**
     * Import workspace from ZIP bytes.
     *
     * @param agentId  the agent ID
     * @param zipData  the ZIP file bytes
     * @param merge    if true, merge with existing; if false, replace
     */
    public void importWorkspace(String agentId, byte[] zipData, boolean merge) throws IOException {
        Path workspaceDir = dataDir.getAgentDir(agentId);

        // If not merging, clear existing workspace
        if (!merge && Files.exists(workspaceDir)) {
            clearDirectory(workspaceDir);
        }

        // Ensure workspace directory exists
        Files.createDirectories(workspaceDir);

        // Extract ZIP
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = workspaceDir.resolve(entry.getName()).normalize();

                // Security check: ensure entry is within workspace
                if (!entryPath.startsWith(workspaceDir)) {
                    log.warn("Skipping unsafe zip entry: {}", entry.getName());
                    continue;
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(zis, entryPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }

                zis.closeEntry();
            }
        }
    }

    /**
     * Validate ZIP data.
     *
     * @param zipData the ZIP file bytes
     * @return true if valid
     */
    public boolean validateZip(byte[] zipData) {
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zipData))) {
            // Try to read first entry
            ZipEntry entry = zis.getNextEntry();
            return entry != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Clear a directory (remove all contents but keep directory).
     */
    private void clearDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        Files.walk(directory)
                .sorted(Comparator.reverseOrder())
                .filter(path -> !path.equals(directory))
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        log.warn("Failed to delete {}: {}", path, e.getMessage());
                    }
                });
    }
}
