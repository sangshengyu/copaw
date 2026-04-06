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
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Tool for searching file contents using patterns (similar to grep).
 *
 * <p>Provides capabilities:
 * <ul>
 *   <li>Search text files for patterns</li>
 *   <li>Support for regex and literal string matching</li>
 *   <li>Recursive directory search</li>
 *   <li>File pattern filtering (e.g., "*.java")</li>
 *   <li>Context lines around matches</li>
 * </ul>
 *
 * <p>Security: Binary files and certain directories are skipped automatically.
 */
public class GrepSearchTool {

    private static final Logger logger = LoggerFactory.getLogger(GrepSearchTool.class);

    /**
     * Base directory to restrict file access. If null, no restriction is applied.
     */
    private final Path baseDir;

    /**
     * Maximum file size to search (2 MB).
     */
    private static final long MAX_FILE_SIZE = 2 * 1024 * 1024;

    /**
     * Maximum number of matches to return.
     */
    private static final int MAX_MATCHES = 200;

    /**
     * Maximum output size in characters (~50KB).
     */
    private static final int MAX_OUTPUT_CHARS = 50000;

    /**
     * Maximum context lines before/after matches.
     */
    private static final int MAX_CONTEXT_LINES = 5;

    /**
     * Binary file extensions to skip.
     */
    private static final Set<String> BINARY_EXTENSIONS =
            Set.of(
                    ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".webp", ".svg",
                    ".mp3", ".mp4", ".avi", ".mov", ".mkv", ".flac", ".wav",
                    ".zip", ".tar", ".gz", ".bz2", ".7z", ".rar",
                    ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
                    ".exe", ".dll", ".so", ".dylib", ".bin", ".dat",
                    ".woff", ".woff2", ".ttf", ".eot", ".otf",
                    ".pyc", ".pyo", ".class", ".o", ".a");

    /**
     * Directories to skip during recursive search.
     */
    private static final Set<String> SKIP_DIRS =
            Set.of(
                    ".git", ".svn", ".hg", "node_modules", "__pycache__", ".tox", ".nox",
                    ".mypy_cache", ".pytest_cache", ".ruff_cache", ".venv", "venv", ".eggs",
                    "dist", "build", ".next", ".nuxt", "target");

    /**
     * Creates a GrepSearchTool with no base directory restriction.
     */
    public GrepSearchTool() {
        this(null);
    }

    /**
     * Creates a GrepSearchTool with a base directory restriction.
     *
     * @param baseDir The base directory to restrict file access to. If null, no restriction is applied.
     */
    public GrepSearchTool(String baseDir) {
        this.baseDir = baseDir != null ? Paths.get(baseDir).toAbsolutePath().normalize() : null;
        if (this.baseDir != null) {
            logger.info("GrepSearchTool initialized with base directory: {}", this.baseDir);
        } else {
            logger.info("GrepSearchTool initialized without base directory restriction");
        }
    }

    /**
     * Search file contents for a pattern (similar to grep).
     *
     * @param pattern The search pattern
     * @param path The file or directory to search in (default: workspace directory)
     * @param isRegex Whether to treat pattern as regex (default: false)
     * @param caseSensitive Whether matching is case-sensitive (default: true)
     * @param contextLines Number of context lines around matches (default: 0, max: 5)
     * @param filePattern Only search files matching this glob pattern (e.g., "*.java")
     * @param recursive Whether to search recursively in directories (default: true)
     * @return The search results
     */
    @Tool(
            name = "grep_search",
            description =
                    "Search file contents for a pattern (similar to grep). Supports regex and"
                        + " literal string matching, with optional file pattern filtering. Use"
                        + " this to find text within files.")
    public Mono<ToolResultBlock> grepSearch(
            @ToolParam(name = "pattern", description = "The search pattern (string or regex)")
                    String pattern,
            @ToolParam(
                            name = "path",
                            description =
                                    "The file or directory to search in. Defaults to workspace"
                                        + " directory.",
                            required = false)
                    String path,
            @ToolParam(
                            name = "is_regex",
                            description = "Whether to treat pattern as regex. Default: false",
                            required = false)
                    Boolean isRegex,
            @ToolParam(
                            name = "case_sensitive",
                            description = "Whether matching is case-sensitive. Default: true",
                            required = false)
                    Boolean caseSensitive,
            @ToolParam(
                            name = "context_lines",
                            description = "Number of context lines around matches (0-5). Default: 0",
                            required = false)
                    Integer contextLines,
            @ToolParam(
                            name = "file_pattern",
                            description =
                                    "Only search files matching this glob pattern (e.g.,\"*.java\","
                                        + " \"*.py\"). Default: all text files.",
                            required = false)
                    String filePattern,
            @ToolParam(
                            name = "recursive",
                            description = "Whether to search recursively in directories. Default: true",
                            required = false)
                    Boolean recursive) {

        if (pattern == null || pattern.trim().isEmpty()) {
            return Mono.just(ToolResultBlock.error("Error: Search pattern cannot be empty."));
        }

        boolean useRegex = isRegex != null && isRegex;
        boolean caseSensitiveMatch = caseSensitive == null || caseSensitive;
        int ctxLines = (contextLines != null) ? contextLines : 0;
        if (ctxLines < 0) {
            ctxLines = 0;
        } else if (ctxLines > MAX_CONTEXT_LINES) {
            ctxLines = MAX_CONTEXT_LINES;
        }
        boolean isRecursive = recursive == null || recursive;

        logger.debug(
                "grep_search called: pattern='{}', path='{}', isRegex={}, caseSensitive={},"
                        + " contextLines={}, filePattern='{}', recursive={}",
                pattern,
                path,
                useRegex,
                caseSensitiveMatch,
                ctxLines,
                filePattern,
                isRecursive);

        // Determine search root
        Path searchRoot;
        try {
            if (path == null || path.trim().isEmpty()) {
                searchRoot = (baseDir != null) ? baseDir : Paths.get(".").toAbsolutePath().normalize();
            } else {
                searchRoot = validatePath(path, baseDir);
            }
        } catch (Exception e) {
            return Mono.just(ToolResultBlock.error("Error: Invalid path: " + e.getMessage()));
        }

        // Check if path exists
        if (!Files.exists(searchRoot)) {
            return Mono.just(
                    ToolResultBlock.error(
                            String.format("The path %s does not exist.", path)));
        }

        // Compile pattern
        final Pattern compiledPattern;
        try {
            int flags = caseSensitiveMatch ? 0 : Pattern.CASE_INSENSITIVE;
            String regexPattern = useRegex ? pattern : Pattern.quote(pattern);
            compiledPattern = Pattern.compile(regexPattern, flags);
        } catch (PatternSyntaxException e) {
            return Mono.just(
                    ToolResultBlock.error("Error: Invalid regex pattern: " + e.getMessage()));
        }

        final Path finalSearchRoot = searchRoot;
        final String finalFilePattern = filePattern;
        final int finalContextLines = ctxLines;

        // Execute search in a separate thread
        return Mono.fromCallable(
                        () ->
                                doSearch(
                                        finalSearchRoot,
                                        compiledPattern,
                                        finalFilePattern,
                                        finalContextLines,
                                        isRecursive))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(
                        e -> {
                            logger.error("Error during grep search: {}", e.getMessage(), e);
                            return Mono.just(
                                    ToolResultBlock.error(
                                            "Error during search: " + e.getMessage()));
                        });
    }

    /**
     * Perform the actual search.
     */
    private ToolResultBlock doSearch(
            Path searchRoot,
            Pattern pattern,
            String filePattern,
            int contextLines,
            boolean recursive)
            throws IOException {

        List<MatchResult> allMatches = new ArrayList<>();
        int[] filesScanned = {0};

        if (Files.isRegularFile(searchRoot)) {
            // Single file search
            if (shouldSearchFile(searchRoot, filePattern)) {
                filesScanned[0]++;
                searchFile(searchRoot, searchRoot, pattern, contextLines, allMatches);
            }
        } else if (Files.isDirectory(searchRoot) && recursive) {
            // Recursive directory search
            Files.walkFileTree(
                    searchRoot,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(
                                Path dir, BasicFileAttributes attrs) {
                            String dirName = dir.getFileName().toString();
                            if (SKIP_DIRS.contains(dirName)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (allMatches.size() >= MAX_MATCHES) {
                                return FileVisitResult.TERMINATE;
                            }
                            if (shouldSearchFile(file, filePattern)) {
                                filesScanned[0]++;
                                try {
                                    searchFile(file, searchRoot, pattern, contextLines, allMatches);
                                } catch (IOException e) {
                                    logger.warn("Error searching file {}: {}", file, e.getMessage());
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } else if (Files.isDirectory(searchRoot)) {
            // Non-recursive directory search - just list files
            try (var stream = Files.list(searchRoot)) {
                List<Path> files = stream.filter(Files::isRegularFile).collect(Collectors.toList());
                for (Path file : files) {
                    if (allMatches.size() >= MAX_MATCHES) {
                        break;
                    }
                    if (shouldSearchFile(file, filePattern)) {
                        filesScanned[0]++;
                        searchFile(file, searchRoot, pattern, contextLines, allMatches);
                    }
                }
            }
        }

        // Format results
        if (allMatches.isEmpty()) {
            return ToolResultBlock.text(
                    String.format(
                            "No matches found for pattern '%s' in %s (scanned %d files).",
                            pattern.pattern(), searchRoot, filesScanned[0]));
        }

        StringBuilder result = new StringBuilder();
        result.append(
                String.format(
                        "Found %d match(es) for pattern '%s' (scanned %d files):\n\n",
                        allMatches.size(), pattern.pattern(), filesScanned[0]));

        // Group matches by file
        var matchesByFile =
                allMatches.stream().collect(Collectors.groupingBy(m -> m.filePath));

        int totalChars = result.length();
        boolean truncated = false;

        for (var entry : matchesByFile.entrySet()) {
            if (truncated) {
                break;
            }

            String filePath = entry.getKey();
            List<MatchResult> fileMatches = entry.getValue();

            String fileHeader = filePath + ":\n";
            if (totalChars + fileHeader.length() > MAX_OUTPUT_CHARS) {
                truncated = true;
                break;
            }
            result.append(fileHeader);
            totalChars += fileHeader.length();

            for (MatchResult match : fileMatches) {
                String matchStr = formatMatch(match, contextLines);
                if (totalChars + matchStr.length() > MAX_OUTPUT_CHARS) {
                    truncated = true;
                    break;
                }
                result.append(matchStr);
                totalChars += matchStr.length();
            }

            result.append("\n");
            totalChars += 1;
        }

        if (truncated) {
            result.append(
                    "\n(Results truncated due to output size limit. Try a more specific pattern or narrower path.)");
        }

        return ToolResultBlock.text(result.toString().trim());
    }

    /**
     * Check if a file should be searched.
     */
    private boolean shouldSearchFile(Path file, String filePattern) {
        // Skip binary files
        String fileName = file.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            String ext = fileName.substring(dotIndex).toLowerCase();
            if (BINARY_EXTENSIONS.contains(ext)) {
                return false;
            }
        }

        // Check file size
        try {
            long size = Files.size(file);
            if (size > MAX_FILE_SIZE) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }

        // Check file pattern
        if (filePattern != null && !filePattern.isEmpty()) {
            // Convert glob to regex-like matching
            String glob = filePattern;
            if (glob.startsWith("*")) {
                glob = glob.substring(1);
                if (!fileName.endsWith(glob.substring(1))) {
                    return false;
                }
            } else if (!fileName.equals(glob)) {
                // Simple contains check for patterns like "*.java"
                if (glob.startsWith("*.")) {
                    String ext = glob.substring(1);
                    if (!fileName.endsWith(ext)) {
                        return false;
                    }
                } else {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Search a single file for matches.
     */
    private void searchFile(
            Path file, Path searchRoot, Pattern pattern, int contextLines, List<MatchResult> matches)
            throws IOException {

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String relativePath = searchRoot.relativize(file).toString();

        for (int i = 0; i < lines.size(); i++) {
            if (matches.size() >= MAX_MATCHES) {
                return;
            }

            String line = lines.get(i);
            if (pattern.matcher(line).find()) {
                // Collect context lines
                List<String> contextBefore = new ArrayList<>();
                List<String> contextAfter = new ArrayList<>();

                for (int j = Math.max(0, i - contextLines); j < i; j++) {
                    contextBefore.add(lines.get(j));
                }
                for (int j = i + 1; j < Math.min(lines.size(), i + 1 + contextLines); j++) {
                    contextAfter.add(lines.get(j));
                }

                matches.add(
                        new MatchResult(
                                relativePath, i + 1, line, contextBefore, contextAfter));
            }
        }
    }

    /**
     * Format a match result for display.
     */
    private String formatMatch(MatchResult match, int contextLines) {
        StringBuilder sb = new StringBuilder();

        // Context before
        for (int i = 0; i < match.contextBefore.size(); i++) {
            int lineNum = match.lineNumber - match.contextBefore.size() + i;
            sb.append(String.format("  %d: %s\n", lineNum, match.contextBefore.get(i)));
        }

        // Match line
        sb.append(String.format("> %d: %s\n", match.lineNumber, match.line));

        // Context after
        for (int i = 0; i < match.contextAfter.size(); i++) {
            int lineNum = match.lineNumber + 1 + i;
            sb.append(String.format("  %d: %s\n", lineNum, match.contextAfter.get(i)));
        }

        if (contextLines > 0) {
            sb.append("  ---\n");
        }

        return sb.toString();
    }

    /**
     * Validate that the given file path is within the base directory.
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
     * Data class for a match result.
     */
    private static class MatchResult {
        final String filePath;
        final int lineNumber;
        final String line;
        final List<String> contextBefore;
        final List<String> contextAfter;

        MatchResult(
                String filePath,
                int lineNumber,
                String line,
                List<String> contextBefore,
                List<String> contextAfter) {
            this.filePath = filePath;
            this.lineNumber = lineNumber;
            this.line = line;
            this.contextBefore = Collections.unmodifiableList(contextBefore);
            this.contextAfter = Collections.unmodifiableList(contextAfter);
        }
    }
}
