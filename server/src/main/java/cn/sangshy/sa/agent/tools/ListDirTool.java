/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.sangshy.sa.agent.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Tool for listing directory contents with optional recursive listing.
 *
 * <p>Provides capabilities:
 * <ul>
 *   <li>List files and directories in a specified path</li>
 *   <li>Recursive listing with configurable max depth</li>
 *   <li>Display file sizes and modification times</li>
 * </ul>
 *
 * <p>Security: When baseDir is specified, all file operations are restricted to that directory
 * to prevent unauthorized file access.
 */
public class ListDirTool {

    private static final Logger logger = LoggerFactory.getLogger(ListDirTool.class);

    /**
     * Base directory to restrict file access. If null, no restriction is applied.
     * This prevents path traversal attacks and unauthorized file access.
     */
    private final Path baseDir;

    /**
     * Default maximum depth for recursive listing.
     */
    private static final int DEFAULT_MAX_DEPTH = 3;

    /**
     * Maximum allowed depth for recursive listing to prevent performance issues.
     */
    private static final int MAX_ALLOWED_DEPTH = 10;

    /**
     * Creates a ListDirTool with no base directory restriction.
     */
    public ListDirTool() {
        this(null);
    }

    /**
     * Creates a ListDirTool with a base directory restriction.
     *
     * @param baseDir The base directory to restrict file access to. If null, no restriction is applied.
     */
    public ListDirTool(String baseDir) {
        this.baseDir = baseDir != null ? Paths.get(baseDir).toAbsolutePath().normalize() : null;
        if (this.baseDir != null) {
            logger.info("ListDirTool initialized with base directory: {}", this.baseDir);
        } else {
            logger.info("ListDirTool initialized without base directory restriction");
        }
    }

    /**
     * List directory contents. Supports both flat and recursive listing.
     *
     * @param path The directory path to list
     * @param recursive Whether to list recursively (default: false)
     * @param maxDepth Maximum depth for recursive listing (default: 3, max: 10)
     * @return The directory listing result
     */
    @Tool(
            name = "list_dir",
            description =
                    "List directory contents. Shows files and subdirectories in the specified path."
                        + " Supports recursive listing with configurable depth. Use this to explore"
                        + " the file system structure.")
    public Mono<ToolResultBlock> listDir(
            @ToolParam(
                            name = "path",
                            description = "The directory path to list. If not provided, uses the workspace directory.",
                            required = false)
                    String path,
            @ToolParam(
                            name = "recursive",
                            description = "Whether to list recursively. Default: false",
                            required = false)
                    Boolean recursive,
            @ToolParam(
                            name = "max_depth",
                            description = "Maximum depth for recursive listing (default: 3, max: 10)",
                            required = false)
                    Integer maxDepth) {

        boolean isRecursive = recursive != null && recursive;
        int depth = (maxDepth != null) ? maxDepth : DEFAULT_MAX_DEPTH;

        // Clamp depth to reasonable limits
        if (depth < 1) {
            depth = 1;
        } else if (depth > MAX_ALLOWED_DEPTH) {
            depth = MAX_ALLOWED_DEPTH;
        }

        logger.debug(
                "list_dir called: path='{}', recursive={}, maxDepth={}", path, isRecursive, depth);

        final int finalDepth = depth;

        return Mono.fromCallable(
                        () -> {
                            // Determine target path
                            Path targetPath;
                            if (path == null || path.trim().isEmpty()) {
                                targetPath = (baseDir != null) ? baseDir : Paths.get(".").toAbsolutePath().normalize();
                            } else {
                                targetPath = validatePath(path, baseDir);
                            }

                            // Check if path exists
                            if (!Files.exists(targetPath)) {
                                logger.warn("Directory does not exist: {}", path);
                                return ToolResultBlock.error(
                                        String.format("The directory %s does not exist.", path));
                            }

                            // Check if path is a directory
                            if (!Files.isDirectory(targetPath)) {
                                logger.warn("Path is not a directory: {}", path);
                                return ToolResultBlock.error(
                                        String.format("The path %s is not a directory.", path));
                            }

                            // Perform listing
                            if (isRecursive) {
                                return listRecursive(targetPath, finalDepth);
                            } else {
                                return listFlat(targetPath);
                            }
                        })
                .onErrorResume(
                        e -> {
                            logger.error(
                                    "Error listing directory '{}': {}", path, e.getMessage(), e);
                            return Mono.just(ToolResultBlock.error("Error: " + e.getMessage()));
                        });
    }

    /**
     * Perform flat (non-recursive) directory listing.
     */
    private ToolResultBlock listFlat(Path dirPath) throws IOException {
        List<String> directories = new ArrayList<>();
        List<FileInfo> files = new ArrayList<>();

        try (Stream<Path> paths = Files.list(dirPath)) {
            List<Path> sortedPaths =
                    paths.sorted(Comparator.comparing(Path::toString))
                            .collect(Collectors.toList());

            for (Path p : sortedPaths) {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    directories.add(name + "/");
                } else {
                    long size = Files.size(p);
                    files.add(new FileInfo(name, size));
                }
            }
        }

        StringBuilder result = new StringBuilder();
        result.append(String.format("Contents of %s:\n\n", dirPath));

        if (!directories.isEmpty()) {
            result.append("Directories:\n");
            for (String dir : directories) {
                result.append("  ").append(dir).append("\n");
            }
            result.append("\n");
        }

        if (!files.isEmpty()) {
            result.append("Files:\n");
            for (FileInfo file : files) {
                result.append(String.format("  %-40s %10s\n", file.name, formatSize(file.size)));
            }
            result.append("\n");
        }

        if (directories.isEmpty() && files.isEmpty()) {
            result.append("(empty directory)\n");
        } else {
            result.append(
                    String.format(
                            "Total: %d directorie(s), %d file(s)",
                            directories.size(), files.size()));
        }

        logger.debug(
                "Listed {} directories and {} files in: {}",
                directories.size(),
                files.size(),
                dirPath);

        return ToolResultBlock.text(result.toString());
    }

    /**
     * Perform recursive directory listing.
     */
    private ToolResultBlock listRecursive(Path dirPath, int maxDepth) throws IOException {
        StringBuilder result = new StringBuilder();
        result.append(String.format("Recursive listing of %s (max depth: %d):\n\n", dirPath, maxDepth));

        List<String> lines = new ArrayList<>();
        int[] stats = new int[2]; // [directories, files]

        listRecursiveHelper(dirPath, dirPath, "", 0, maxDepth, lines, stats);

        if (lines.isEmpty()) {
            result.append("(empty directory)\n");
        } else {
            for (String line : lines) {
                result.append(line).append("\n");
            }
        }

        result.append(
                String.format("\nTotal: %d directorie(s), %d file(s)", stats[0], stats[1]));

        logger.debug(
                "Recursively listed {} directories and {} files in: {}",
                stats[0],
                stats[1],
                dirPath);

        return ToolResultBlock.text(result.toString());
    }

    /**
     * Helper method for recursive listing.
     */
    private void listRecursiveHelper(
            Path rootPath,
            Path currentPath,
            String prefix,
            int currentDepth,
            int maxDepth,
            List<String> lines,
            int[] stats)
            throws IOException {

        if (currentDepth > maxDepth) {
            return;
        }

        List<Path> entries;
        try (Stream<Path> paths = Files.list(currentPath)) {
            entries = paths.sorted(Comparator.comparing(Path::toString)).collect(Collectors.toList());
        }

        for (int i = 0; i < entries.size(); i++) {
            Path entry = entries.get(i);
            boolean isLast = (i == entries.size() - 1);
            String connector = isLast ? "└── " : "├── ";
            String childPrefix = isLast ? "    " : "│   ";

            String name = entry.getFileName().toString();

            if (Files.isDirectory(entry)) {
                stats[0]++;
                lines.add(prefix + connector + name + "/");
                listRecursiveHelper(
                        rootPath, entry, prefix + childPrefix, currentDepth + 1, maxDepth, lines, stats);
            } else {
                stats[1]++;
                long size = Files.size(entry);
                lines.add(prefix + connector + String.format("%-30s %10s", name, formatSize(size)));
            }
        }
    }

    /**
     * Format file size in human-readable format.
     */
    private String formatSize(long size) {
        if (size < 1024) {
            return size + " B";
        } else if (size < 1024 * 1024) {
            return String.format("%.1f KB", size / 1024.0);
        } else if (size < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", size / (1024.0 * 1024.0));
        } else {
            return String.format("%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
        }
    }

    /**
     * Validate that the given file path is within the base directory.
     *
     * @param filePath The file path to validate
     * @param baseDir The base directory to restrict access to (null means no restriction)
     * @return The normalized absolute path if valid
     * @throws IOException if the path is invalid or outside the base directory
     */
    private Path validatePath(String filePath, Path baseDir) throws IOException {
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IOException("File path cannot be null or empty.");
        }

        Path inputPath = Paths.get(filePath);

        Path path;
        if (baseDir != null && !inputPath.isAbsolute()) {
            path = baseDir.resolve(inputPath).normalize();
        } else {
            path = inputPath.toAbsolutePath().normalize();
        }

        if (baseDir != null) {
            Path normalizedBaseDir = baseDir.toAbsolutePath().normalize();
            if (!path.startsWith(normalizedBaseDir)) {
                throw new IOException(
                        String.format(
                                "Access denied: The file path '%s' is outside the allowed base"
                                        + " directory '%s'.",
                                filePath, normalizedBaseDir));
            }
        }

        return path;
    }

    /**
     * Simple data class for file information.
     */
    private static class FileInfo {
        final String name;
        final long size;

        FileInfo(String name, long size) {
            this.name = name;
            this.size = size;
        }
    }
}
