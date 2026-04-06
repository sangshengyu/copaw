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
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Tool for searching files by glob patterns.
 *
 * <p>Provides capabilities:
 * <ul>
 *   <li>Find files matching glob patterns</li>
 *   <li>Search from a specified base path</li>
 *   <li>Return sorted list of matching files</li>
 * </ul>
 *
 * <p>Security: Certain directories are skipped automatically during search.
 */
public class GlobSearchTool {

    private static final Logger logger = LoggerFactory.getLogger(GlobSearchTool.class);

    /**
     * Base directory to restrict file access. If null, no restriction is applied.
     */
    private final Path baseDir;

    /**
     * Maximum number of results to return.
     */
    private static final int MAX_RESULTS = 200;

    /**
     * Directories to skip during search.
     */
    private static final Set<String> SKIP_DIRS =
            Set.of(
                    ".git", ".svn", ".hg", "node_modules", "__pycache__", ".tox", ".nox",
                    ".mypy_cache", ".pytest_cache", ".ruff_cache", ".venv", "venv", ".eggs",
                    "dist", "build", ".next", ".nuxt", "target");

    /**
     * Creates a GlobSearchTool with no base directory restriction.
     */
    public GlobSearchTool() {
        this(null);
    }

    /**
     * Creates a GlobSearchTool with a base directory restriction.
     *
     * @param baseDir The base directory to restrict file access to. If null, no restriction is applied.
     */
    public GlobSearchTool(String baseDir) {
        this.baseDir = baseDir != null ? Paths.get(baseDir).toAbsolutePath().normalize() : null;
        if (this.baseDir != null) {
            logger.info("GlobSearchTool initialized with base directory: {}", this.baseDir);
        } else {
            logger.info("GlobSearchTool initialized without base directory restriction");
        }
    }

    @Tool(
            name = "glob_search",
            description =
                    "Find files matching a glob pattern. Supports standard glob syntax"
                        + " including wildcards. Use this to discover files by name pattern.")
    public Mono<ToolResultBlock> globSearch(
            @ToolParam(
                            name = "pattern",
                            description =
                                    "The glob pattern to match (e.g., .java, .json, src/.py)")
                    String pattern,
            @ToolParam(
                            name = "base_path",
                            description =
                                    "The base directory to search from. Defaults to workspace"
                                        + " directory.",
                            required = false)
                    String basePath) {

        if (pattern == null || pattern.trim().isEmpty()) {
            return Mono.just(ToolResultBlock.error("Error: Glob pattern cannot be empty."));
        }

        String globPattern = pattern.trim();

        logger.debug("glob_search called: pattern='{}', basePath='{}'", pattern, basePath);

        // Determine search root
        Path searchRoot;
        try {
            if (basePath == null || basePath.trim().isEmpty()) {
                searchRoot = (baseDir != null) ? baseDir : Paths.get(".").toAbsolutePath().normalize();
            } else {
                searchRoot = validatePath(basePath, baseDir);
            }
        } catch (Exception e) {
            return Mono.just(ToolResultBlock.error("Error: Invalid base path: " + e.getMessage()));
        }

        // Check if path exists and is a directory
        if (!Files.exists(searchRoot)) {
            return Mono.just(
                    ToolResultBlock.error(
                            String.format("The path %s does not exist.", basePath)));
        }

        if (!Files.isDirectory(searchRoot)) {
            return Mono.just(
                    ToolResultBlock.error(
                            String.format("The path %s is not a directory.", basePath)));
        }

        final Path finalSearchRoot = searchRoot;

        // Execute search in a separate thread
        return Mono.fromCallable(() -> doGlobSearch(finalSearchRoot, globPattern))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(
                        e -> {
                            logger.error("Error during glob search: {}", e.getMessage(), e);
                            return Mono.just(
                                    ToolResultBlock.error(
                                            "Error during search: " + e.getMessage()));
                        });
    }

    /**
     * Perform the actual glob search.
     */
    private ToolResultBlock doGlobSearch(Path searchRoot, String pattern) throws IOException {
        List<Path> matches = new ArrayList<>();

        // Normalize pattern
        String globPattern = pattern;
        if (!pattern.startsWith("glob:") && !pattern.startsWith("regex:")) {
            globPattern = "glob:" + pattern;
        }

        FileSystem fs = FileSystems.getDefault();
        PathMatcher matcher = fs.getPathMatcher(globPattern);

        Files.walkFileTree(
                searchRoot,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        String dirName = dir.getFileName().toString();
                        if (SKIP_DIRS.contains(dirName)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (matches.size() >= MAX_RESULTS) {
                            return FileVisitResult.TERMINATE;
                        }

                        try {
                            // Try to match against relative path
                            Path relativePath = searchRoot.relativize(file);
                            if (matcher.matches(relativePath) || matcher.matches(file.getFileName())) {
                                matches.add(file);
                            }
                        } catch (Exception e) {
                            // If relative matching fails, try file name only
                            if (matcher.matches(file.getFileName())) {
                                matches.add(file);
                            }
                        }

                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });

        // Sort results
        Collections.sort(matches);

        // Format results
        if (matches.isEmpty()) {
            return ToolResultBlock.text(
                    String.format(
                            "No files matched pattern '%s' in %s.", pattern, searchRoot));
        }

        StringBuilder result = new StringBuilder();
        result.append(
                String.format(
                        "Found %d file(s) matching pattern '%s':\n\n",
                        matches.size(), pattern));

        for (Path match : matches) {
            try {
                String relativePath = searchRoot.relativize(match).toString();
                result.append(relativePath).append("\n");
            } catch (Exception e) {
                // Fallback to absolute path
                result.append(match.toString()).append("\n");
            }
        }

        if (matches.size() >= MAX_RESULTS) {
            result.append(
                    String.format(
                            "\n(Results limited to %d entries. Try a more specific pattern.)",
                            MAX_RESULTS));
        }

        logger.debug(
                "Glob search for '{}' found {} matches in {}",
                pattern,
                matches.size(),
                searchRoot);

        return ToolResultBlock.text(result.toString().trim());
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
}
