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
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Tool for executing shell commands with safety restrictions.
 *
 * <p>Provides capabilities:
 * <ul>
 *   <li>Execute shell commands with configurable timeout</li>
 *   <li>Specify working directory for command execution</li>
 *   <li>Block dangerous commands (rm -rf, etc.)</li>
 *   <li>Return stdout, stderr, and exit code</li>
 * </ul>
 *
 * <p>Security: Dangerous commands are blocked to prevent accidental or malicious damage.
 */
public class ExecuteShellTool {

    private static final Logger logger = LoggerFactory.getLogger(ExecuteShellTool.class);

    /**
     * Base directory to restrict file access. If null, no restriction is applied.
     */
    private final Path baseDir;

    /**
     * Default timeout in seconds.
     */
    private static final int DEFAULT_TIMEOUT = 30;

    /**
     * Maximum allowed timeout in seconds.
     */
    private static final int MAX_TIMEOUT = 300; // 5 minutes

    /**
     * Set of dangerous commands/patterns that are blocked.
     */
    private static final Set<String> DANGEROUS_PATTERNS =
            new HashSet<>(
                    Arrays.asList(
                            "rm -rf /",
                            "rm -rf /*",
                            "rm -rf ~",
                            "rm -rf ~/*",
                            ":(){ :|:& };:", // Fork bomb
                            "> /dev/sda",
                            "dd if=/dev/zero of=/dev/sda",
                            "mkfs.ext3 /dev/sda",
                            "mv / /dev/null",
                            "chmod -R 777 /",
                            "chown -R /",
                            "del /f /s /q \\*", // Windows dangerous delete
                            "rmdir /s /q \\",
                            "format c:",
                            "format c:/",
                            "rd /s /q c:\\"));

    /**
     * Set of dangerous command starters that require extra scrutiny.
     */
    private static final Set<String> DANGEROUS_COMMANDS =
            new HashSet<>(Arrays.asList("rm", "del", "rmdir", "rd", "format", "dd", "mkfs"));

    /**
     * Creates an ExecuteShellTool with no base directory restriction.
     */
    public ExecuteShellTool() {
        this(null);
    }

    /**
     * Creates an ExecuteShellTool with a base directory restriction.
     *
     * @param baseDir The base directory to restrict file access to. If null, no restriction is applied.
     */
    public ExecuteShellTool(String baseDir) {
        this.baseDir = baseDir != null ? Paths.get(baseDir).toAbsolutePath().normalize() : null;
        if (this.baseDir != null) {
            logger.info("ExecuteShellTool initialized with base directory: {}", this.baseDir);
        } else {
            logger.info("ExecuteShellTool initialized without base directory restriction");
        }
    }

    /**
     * Execute a shell command and return its output.
     *
     * @param command The shell command to execute
     * @param workingDir The working directory for command execution (optional)
     * @param timeout Maximum execution time in seconds (default: 30, max: 300)
     * @return The command execution result
     */
    @Tool(
            name = "execute_shell",
            description =
                    "Execute a shell command and return its output. Supports specifying working"
                        + " directory and timeout. Dangerous commands (rm -rf, etc.) are blocked"
                        + " for safety. Use this to run system commands, build tools, or scripts.")
    public Mono<ToolResultBlock> executeShell(
            @ToolParam(name = "command", description = "The shell command to execute")
                    String command,
            @ToolParam(
                            name = "working_dir",
                            description =
                                    "The working directory for command execution. If not provided,"
                                        + " uses the workspace directory.",
                            required = false)
                    String workingDir,
            @ToolParam(
                            name = "timeout",
                            description =
                                    "Maximum execution time in seconds (default: 30, max: 300)",
                            required = false)
                    Integer timeout) {

        // Validate and sanitize timeout
        int execTimeout = (timeout != null) ? timeout : DEFAULT_TIMEOUT;
        if (execTimeout < 1) {
            execTimeout = 1;
        } else if (execTimeout > MAX_TIMEOUT) {
            execTimeout = MAX_TIMEOUT;
        }

        logger.debug(
                "execute_shell called: command='{}', workingDir='{}', timeout={}",
                command,
                workingDir,
                execTimeout);

        if (command == null || command.trim().isEmpty()) {
            return Mono.just(ToolResultBlock.error("Error: Command cannot be empty."));
        }

        // Sanitize command - remove embedded newlines
        final String sanitizedCommand = sanitizeCommand(command.trim());

        // Check for dangerous commands
        String dangerCheck = checkDangerousCommand(sanitizedCommand);
        if (dangerCheck != null) {
            logger.warn("Blocked dangerous command: {}", sanitizedCommand);
            return Mono.just(
                    ToolResultBlock.error(
                            "Error: Command blocked for security reasons: " + dangerCheck));
        }

        // Determine working directory
        Path workDir;
        try {
            if (workingDir == null || workingDir.trim().isEmpty()) {
                workDir = (baseDir != null) ? baseDir : Paths.get(".").toAbsolutePath().normalize();
            } else {
                workDir = validateWorkingDir(workingDir);
            }
        } catch (Exception e) {
            return Mono.just(ToolResultBlock.error("Error: Invalid working directory: " + e.getMessage()));
        }

        // Check if working directory exists
        if (!workDir.toFile().exists()) {
            return Mono.just(
                    ToolResultBlock.error(
                            "Error: Working directory does not exist: " + workDir));
        }

        final Path finalWorkDir = workDir;
        final int finalTimeout = execTimeout;

        // Execute command in a separate thread
        return Mono.fromCallable(() -> executeCommand(sanitizedCommand, finalWorkDir, finalTimeout))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(
                        e -> {
                            logger.error(
                                    "Error executing command '{}': {}",
                                    sanitizedCommand,
                                    e.getMessage(),
                                    e);
                            return Mono.just(
                                    ToolResultBlock.error(
                                            "Error executing command: " + e.getMessage()));
                        });
    }

    /**
     * Sanitize command by removing embedded newlines and fixing common issues.
     */
    private String sanitizeCommand(String command) {
        // Replace embedded newlines with spaces
        String sanitized = command.replace("\r\n", " ").replace("\n", " ");

        // Fix Windows cmd escaping artifacts
        if (sanitized.contains("\\\"") && !sanitized.replace("\\\"", "").contains("\"")) {
            sanitized = sanitized.replace("\\\"", "\"");
        }

        return sanitized.trim();
    }

    /**
     * Check if a command is dangerous. Returns null if safe, or a reason string if dangerous.
     */
    private String checkDangerousCommand(String command) {
        String lowerCommand = command.toLowerCase(Locale.ROOT);

        // Check against exact dangerous patterns
        for (String pattern : DANGEROUS_PATTERNS) {
            if (lowerCommand.contains(pattern.toLowerCase(Locale.ROOT))) {
                return "Command matches dangerous pattern: " + pattern;
            }
        }

        // Check for rm -rf without path restrictions
        if (lowerCommand.matches(".*\\brm\\s+-[a-zA-Z]*f.*-?[a-zA-Z]*r.*\\s+/.*")) {
            return "rm -rf on system directories is not allowed";
        }

        // Check for rm -rf on home directory
        if (lowerCommand.matches(".*\\brm\\s+-[a-zA-Z]*f.*-?[a-zA-Z]*r.*\\s+~.*")) {
            return "rm -rf on home directory is not allowed";
        }

        // Check for rm -rf . or rm -rf *
        if (lowerCommand.matches(".*\\brm\\s+(-[a-zA-Z]*f.*-?[a-zA-Z]*r|-[a-zA-Z]*r.*-?[a-zA-Z]*f).*\\s+(\\.|\\*|\\./|\\.\\.).*")) {
            return "rm -rf on current directory or wildcards is risky";
        }

        return null;
    }

    /**
     * Validate and resolve working directory.
     */
    private Path validateWorkingDir(String workingDir) throws IOException {
        Path path = Paths.get(workingDir);

        if (baseDir != null && !path.isAbsolute()) {
            path = baseDir.resolve(path).normalize();
        } else {
            path = path.toAbsolutePath().normalize();
        }

        if (baseDir != null) {
            Path normalizedBaseDir = baseDir.toAbsolutePath().normalize();
            if (!path.startsWith(normalizedBaseDir)) {
                throw new IOException(
                        String.format(
                                "Access denied: The working directory '%s' is outside the allowed"
                                        + " base directory '%s'.",
                                workingDir, normalizedBaseDir));
            }
        }

        return path;
    }

    /**
     * Execute the command and return the result.
     */
    private ToolResultBlock executeCommand(String command, Path workDir, int timeout)
            throws IOException, InterruptedException {

        ProcessBuilder pb = new ProcessBuilder();

        // Determine shell based on OS
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            // Windows
            pb.command("cmd", "/c", command);
        } else {
            // Unix-like (Linux, macOS)
            pb.command("sh", "-c", command);
        }

        pb.directory(workDir.toFile());
        pb.redirectErrorStream(false); // Keep stdout and stderr separate

        logger.info("Executing command: {} in directory: {}", command, workDir);

        Process process = pb.start();

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        // Read stdout
        Thread stdoutReader =
                new Thread(
                        () -> {
                            try (BufferedReader reader =
                                    new BufferedReader(
                                            new InputStreamReader(
                                                    process.getInputStream(),
                                                    StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    stdout.append(line).append("\n");
                                }
                            } catch (IOException e) {
                                logger.warn("Error reading stdout: {}", e.getMessage());
                            }
                        });

        // Read stderr
        Thread stderrReader =
                new Thread(
                        () -> {
                            try (BufferedReader reader =
                                    new BufferedReader(
                                            new InputStreamReader(
                                                    process.getErrorStream(),
                                                    StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    stderr.append(line).append("\n");
                                }
                            } catch (IOException e) {
                                logger.warn("Error reading stderr: {}", e.getMessage());
                            }
                        });

        stdoutReader.start();
        stderrReader.start();

        boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();
            stdoutReader.join(1000);
            stderrReader.join(1000);
            logger.warn("Command timed out after {} seconds: {}", timeout, command);
            return ToolResultBlock.error(
                    String.format(
                            "Command timed out after %d seconds.\n\nPartial stdout:\n%s\n\nPartial stderr:\n%s",
                            timeout,
                            truncateOutput(stdout.toString(), 2000),
                            truncateOutput(stderr.toString(), 1000)));
        }

        // Wait for readers to finish
        stdoutReader.join(1000);
        stderrReader.join(1000);

        int exitCode = process.exitValue();
        String stdoutStr = stdout.toString().trim();
        String stderrStr = stderr.toString().trim();

        logger.debug(
                "Command completed with exit code {}: {}",
                exitCode,
                command.substring(0, Math.min(command.length(), 50)));

        // Build result
        StringBuilder result = new StringBuilder();

        if (exitCode == 0) {
            result.append("Command executed successfully.\n");
        } else {
            result.append(String.format("Command failed with exit code %d.\n", exitCode));
        }

        if (!stdoutStr.isEmpty()) {
            result.append("\n[stdout]\n");
            result.append(truncateOutput(stdoutStr, 10000));
        }

        if (!stderrStr.isEmpty()) {
            result.append("\n\n[stderr]\n");
            result.append(truncateOutput(stderrStr, 5000));
        }

        if (stdoutStr.isEmpty() && stderrStr.isEmpty()) {
            result.append("(no output)");
        }

        return ToolResultBlock.text(result.toString().trim());
    }

    /**
     * Truncate output if it exceeds max length.
     */
    private String truncateOutput(String output, int maxLength) {
        if (output.length() <= maxLength) {
            return output;
        }
        return output.substring(0, maxLength)
                + String.format(
                        "\n\n... (output truncated, showing %d of %d characters)",
                        maxLength, output.length());
    }
}
