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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Tool for editing file content with find-and-replace or line range replacement.
 *
 * <p>Provides two modes:
 * <ul>
 *   <li><b>Find-and-replace:</b> Replace all occurrences of old_text with new_text</li>
 *   <li><b>Line range replacement:</b> Replace content between start_line and end_line</li>
 * </ul>
 *
 * <p>Security: When baseDir is specified, all file operations are restricted to that directory
 * to prevent unauthorized file access.
 */
public class EditFileTool {

    private static final Logger logger = LoggerFactory.getLogger(EditFileTool.class);

    /**
     * Base directory to restrict file access. If null, no restriction is applied.
     * This prevents path traversal attacks and unauthorized file access.
     */
    private final Path baseDir;

    /**
     * Creates an EditFileTool with no base directory restriction.
     */
    public EditFileTool() {
        this(null);
    }

    /**
     * Creates an EditFileTool with a base directory restriction.
     *
     * @param baseDir The base directory to restrict file access to. If null, no restriction is applied.
     */
    public EditFileTool(String baseDir) {
        this.baseDir = baseDir != null ? Paths.get(baseDir).toAbsolutePath().normalize() : null;
        if (this.baseDir != null) {
            logger.info("EditFileTool initialized with base directory: {}", this.baseDir);
        } else {
            logger.info("EditFileTool initialized without base directory restriction");
        }
    }

    /**
     * Edit a file by replacing text. Supports two modes:
     * 1. Find-and-replace: Provide old_text and new_text to replace all occurrences
     * 2. Line range replacement: Provide start_line, end_line, and content to replace a range
     *
     * @param filePath The target file path
     * @param oldText The text to find and replace (for find-and-replace mode)
     * @param newText The replacement text (for find-and-replace mode)
     * @param startLine The start line for range replacement (1-based, inclusive)
     * @param endLine The end line for range replacement (1-based, inclusive)
     * @param content The new content for range replacement mode
     * @return The result of the edit operation
     */
    @Tool(
            name = "edit_file",
            description =
                    "Edit a file by replacing text. Supports two modes: 1) Find-and-replace:"
                        + " Provide old_text and new_text to replace all occurrences. 2) Line"
                        + " range replacement: Provide start_line, end_line, and content to"
                        + " replace a specific range. Use this for modifying existing files.")
    public Mono<ToolResultBlock> editFile(
            @ToolParam(name = "file_path", description = "The target file path") String filePath,
            @ToolParam(
                            name = "old_text",
                            description =
                                    "The text to find and replace (for find-and-replace mode)."
                                        + " All occurrences will be replaced.",
                            required = false)
                    String oldText,
            @ToolParam(
                            name = "new_text",
                            description = "The replacement text (for find-and-replace mode)",
                            required = false)
                    String newText,
            @ToolParam(
                            name = "start_line",
                            description =
                                    "The start line for range replacement (1-based, inclusive)",
                            required = false)
                    Integer startLine,
            @ToolParam(
                            name = "end_line",
                            description =
                                    "The end line for range replacement (1-based, inclusive)",
                            required = false)
                    Integer endLine,
            @ToolParam(
                            name = "content",
                            description =
                                    "The new content for range replacement mode. Replaces lines"
                                        + " from start_line to end_line.",
                            required = false)
                    String content) {

        logger.debug(
                "edit_file called: filePath='{}', oldText='{}', startLine={}, endLine={}",
                filePath,
                oldText != null ? "[provided]" : "null",
                startLine,
                endLine);

        return Mono.fromCallable(
                        () -> {
                            // Validate path is within base directory
                            Path path;
                            try {
                                path = validatePath(filePath, baseDir);
                            } catch (Exception e) {
                                logger.warn(
                                        "Path validation failed for '{}': {}",
                                        filePath,
                                        e.getMessage());
                                return ToolResultBlock.error(e.getMessage());
                            }

                            // Check if file exists
                            if (!Files.exists(path)) {
                                logger.warn("File does not exist: {}", filePath);
                                return ToolResultBlock.error(
                                        String.format("The file %s does not exist.", filePath));
                            }

                            // Check if path is a file
                            if (!Files.isRegularFile(path)) {
                                logger.warn("Path is not a regular file: {}", filePath);
                                return ToolResultBlock.error(
                                        String.format("The path %s is not a file.", filePath));
                            }

                            // Determine which mode to use
                            boolean isFindReplaceMode =
                                    oldText != null && !oldText.isEmpty() && newText != null;
                            boolean isRangeMode =
                                    startLine != null && endLine != null && content != null;

                            if (isFindReplaceMode && isRangeMode) {
                                return ToolResultBlock.error(
                                        "Error: Cannot use both find-and-replace mode and range"
                                            + " replacement mode. Please specify either old_text"
                                            + " + new_text OR start_line + end_line + content.");
                            }

                            if (!isFindReplaceMode && !isRangeMode) {
                                return ToolResultBlock.error(
                                        "Error: Must specify either find-and-replace parameters"
                                            + " (old_text + new_text) or range replacement"
                                            + " parameters (start_line + end_line + content).");
                            }

                            if (isFindReplaceMode) {
                                return doFindReplace(path, filePath, oldText, newText);
                            } else {
                                return doRangeReplace(
                                        path, filePath, startLine, endLine, content);
                            }
                        })
                .onErrorResume(
                        e -> {
                            logger.error(
                                    "Error editing file '{}': {}", filePath, e.getMessage(), e);
                            return Mono.just(ToolResultBlock.error("Error: " + e.getMessage()));
                        });
    }

    /**
     * Perform find-and-replace operation.
     */
    private ToolResultBlock doFindReplace(
            Path path, String filePath, String oldText, String newText) throws IOException {
        // Read file content
        String fileContent = Files.readString(path, StandardCharsets.UTF_8);

        // Check if old_text exists
        if (!fileContent.contains(oldText)) {
            logger.warn("Text to replace not found in file: {}", filePath);
            return ToolResultBlock.error(
                    String.format(
                            "The text to replace was not found in %s. Please check the exact"
                                + " text including whitespace and line endings.",
                            filePath));
        }

        // Count occurrences
        int count = countOccurrences(fileContent, oldText);

        // Replace all occurrences
        String newContent = fileContent.replace(oldText, newText);

        // Write back
        Files.writeString(path, newContent, StandardCharsets.UTF_8);

        logger.info(
                "Successfully replaced {} occurrence(s) in file: {}", count, filePath);

        return ToolResultBlock.text(
                String.format(
                        "Successfully replaced %d occurrence(s) of the specified text in %s.",
                        count, filePath));
    }

    /**
     * Perform line range replacement operation.
     */
    private ToolResultBlock doRangeReplace(
            Path path, String filePath, int startLine, int endLine, String content)
            throws IOException {
        // Validate line numbers
        if (startLine <= 0 || endLine <= 0) {
            return ToolResultBlock.error(
                    "Error: Line numbers must be positive integers (1-based).");
        }

        if (startLine > endLine) {
            return ToolResultBlock.error(
                    String.format(
                            "Error: start_line (%d) must be less than or equal to end_line (%d).",
                            startLine, endLine));
        }

        // Read original lines
        List<String> originalLines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int totalLines = originalLines.size();

        if (startLine > totalLines) {
            return ToolResultBlock.error(
                    String.format(
                            "Error: start_line (%d) exceeds file length (%d lines).",
                            startLine, totalLines));
        }

        // Build new content
        List<String> newLines = new ArrayList<>();

        // Add lines before start_line
        if (startLine > 1) {
            newLines.addAll(originalLines.subList(0, startLine - 1));
        }

        // Add new content (split by newlines)
        String[] contentLines = content.split("\n", -1);
        for (String line : contentLines) {
            newLines.add(line);
        }

        // Add lines after end_line
        if (endLine < totalLines) {
            newLines.addAll(originalLines.subList(endLine, totalLines));
        }

        // Write back
        Files.write(path, newLines, StandardCharsets.UTF_8);

        int linesReplaced = Math.min(endLine, totalLines) - startLine + 1;
        int newTotalLines = newLines.size();

        logger.info(
                "Successfully replaced lines {}-{} in file: {} ({} lines -> {} lines)",
                startLine,
                endLine,
                filePath,
                totalLines,
                newTotalLines);

        // Calculate view range
        int viewStart = Math.max(1, startLine - 2);
        int viewEnd = Math.min(newTotalLines, startLine + contentLines.length + 1);

        // Get content snippet
        StringBuilder snippet = new StringBuilder();
        for (int i = viewStart - 1; i < viewEnd && i < newLines.size(); i++) {
            snippet.append(String.format("%d: %s\n", i + 1, newLines.get(i)));
        }

        return ToolResultBlock.text(
                String.format(
                        "Successfully replaced lines %d-%d in %s. "
                            + "File now has %d lines (was %d).\n\nUpdated content (lines %d-%d):\n```\n%s```",
                        startLine,
                        endLine,
                        filePath,
                        newTotalLines,
                        totalLines,
                        viewStart,
                        viewEnd,
                        snippet));
    }

    /**
     * Count occurrences of a substring in a string.
     */
    private int countOccurrences(String text, String pattern) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(pattern, index)) != -1) {
            count++;
            index += pattern.length();
        }
        return count;
    }

    /**
     * Validate that the given file path is within the base directory.
     * This prevents path traversal attacks and unauthorized file access.
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

        // If baseDir is specified, relative paths should be resolved relative to baseDir
        Path path;
        if (baseDir != null && !inputPath.isAbsolute()) {
            // Relative path: resolve relative to baseDir
            path = baseDir.resolve(inputPath).normalize();
        } else {
            // Absolute path or no baseDir: convert to absolute path
            path = inputPath.toAbsolutePath().normalize();
        }

        // If baseDir is specified, ensure the path is within it
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
}
