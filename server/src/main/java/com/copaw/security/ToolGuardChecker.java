package com.copaw.security;

import com.copaw.model.config.ToolGuardConfig;
import com.copaw.model.config.ToolGuardRuleConfig;
import com.copaw.service.ConfigService;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.ActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.tool.ToolCallParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Tool Guard security checker for dangerous command interception.
 * Implements AgentScope Hook interface for tool execution interception.
 */
@Component
public class ToolGuardChecker implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ToolGuardChecker.class);

    private final ConfigService configService;
    private volatile ToolGuardConfig config;
    private volatile List<GuardRule> compiledRules = new ArrayList<>();

    // Default dangerous patterns for shell commands
    private static final List<String> DEFAULT_DANGEROUS_PATTERNS = List.of(
        "rm\\s+-rf\\s+/",
        "rm\\s+-rf\\s+[~\\$]",
        "sudo\\s+rm",
        ">\\s*/",
        "dd\\s+if=.*of=/dev/",
        "mkfs\\.",
        "fdisk",
        "format\\s+",
        "del\\s+/f\\s+/s\\s+/q",
        "rmdir\\s+/s",
        "shutdown",
        "reboot",
        "halt",
        "poweroff",
        "init\\s+0",
        "kill\\s+-9",
        "killall",
        "pkill",
        "chmod\\s+-R\\s+777",
        "chmod\\s+-R\\s+000",
        "chown\\s+-R",
        "rm\\s+(-[a-zA-Z]*f[a-zA-Z]*\\s+)?\\/",
        "mv\\s+.*\\s+/dev/null",
        ":(){ :|:& };:"
    );

    // Default path traversal patterns
    private static final List<String> DEFAULT_PATH_TRAVERSAL_PATTERNS = List.of(
        "\\.\\./",
        "\\.\\.\\\\",
        "~\\/\\.",
        "\\$HOME",
        "\\$USERPROFILE",
        "%SYSTEMROOT%",
        "\\\\windows\\",
        "\\\\etc\\",
        "\\\\usr\\",
        "/etc/passwd",
        "/etc/shadow",
        "/root/",
        "C:\\\\Windows",
        "C:\\\\Program\\s+Files"
    );

    public ToolGuardChecker(ConfigService configService) {
        this.configService = configService;
    }

    @PostConstruct
    public void init() {
        reloadConfig();
    }

    /**
     * Reload guard configuration from config service.
     */
    public void reloadConfig() {
        this.config = configService.getToolGuard();
        compileRules();
        log.info("ToolGuard configuration reloaded. Enabled: {}, Rules: {}",
                config.getEnabled(), compiledRules.size());
    }

    /**
     * Compile configured rules into executable patterns.
     */
    private void compileRules() {
        List<GuardRule> rules = new ArrayList<>();

        // Add default rules if no custom rules configured
        if (config.getRules() == null || config.getRules().isEmpty()) {
            // Add shell command protection
            for (String pattern : DEFAULT_DANGEROUS_PATTERNS) {
                try {
                    rules.add(new GuardRule(
                        "dangerous_shell",
                        Pattern.compile(pattern, Pattern.CASE_INSENSITIVE),
                        "Dangerous shell command detected",
                        "HIGH"
                    ));
                } catch (PatternSyntaxException e) {
                    log.warn("Invalid pattern: {}", pattern);
                }
            }

            // Add path traversal protection
            for (String pattern : DEFAULT_PATH_TRAVERSAL_PATTERNS) {
                try {
                    rules.add(new GuardRule(
                        "path_traversal",
                        Pattern.compile(pattern, Pattern.CASE_INSENSITIVE),
                        "Path traversal attempt detected",
                        "HIGH"
                    ));
                } catch (PatternSyntaxException e) {
                    log.warn("Invalid pattern: {}", pattern);
                }
            }
        } else {
            // Compile custom rules
            for (ToolGuardRuleConfig ruleConfig : config.getRules()) {
                if (ruleConfig.getPatterns() != null) {
                    for (String patternStr : ruleConfig.getPatterns()) {
                        try {
                            rules.add(new GuardRule(
                                ruleConfig.getId(),
                                Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE),
                                ruleConfig.getDescription(),
                                ruleConfig.getSeverity()
                            ));
                        } catch (PatternSyntaxException e) {
                            log.warn("Invalid pattern in rule {}: {}", ruleConfig.getId(), patternStr);
                        }
                    }
                }
            }
        }

        this.compiledRules = rules;
    }

    /**
     * Check if tool guard is enabled.
     */
    public boolean isEnabled() {
        return config != null && Boolean.TRUE.equals(config.getEnabled());
    }

    /**
     * Validate a tool call against security rules.
     *
     * @param toolName Tool name
     * @param params Tool parameters
     * @return Validation result
     */
    public ValidationResult validate(String toolName, Map<String, Object> params) {
        if (!isEnabled()) {
            return ValidationResult.allowed();
        }

        // Convert params to string for pattern matching
        String paramsJson = params != null ? params.toString() : "";

        for (GuardRule rule : compiledRules) {
            if (rule.pattern.matcher(paramsJson).find()) {
                log.warn("ToolGuard blocked tool '{}' with rule '{}': {}",
                        toolName, rule.id, rule.description);
                return ValidationResult.blocked(rule.id, rule.description, rule.severity);
            }
        }

        return ValidationResult.allowed();
    }

    /**
     * Validate shell command specifically.
     *
     * @param command Shell command to validate
     * @return Validation result
     */
    public ValidationResult validateShellCommand(String command) {
        if (!isEnabled() || command == null || command.isEmpty()) {
            return ValidationResult.allowed();
        }

        for (GuardRule rule : compiledRules) {
            if ("dangerous_shell".equals(rule.id) || "path_traversal".equals(rule.id)) {
                if (rule.pattern.matcher(command).find()) {
                    log.warn("ToolGuard blocked shell command with rule '{}': {}",
                            rule.id, rule.description);
                    return ValidationResult.blocked(rule.id, rule.description, rule.severity);
                }
            }
        }

        return ValidationResult.allowed();
    }

    /**
     * Validate file path for traversal attacks.
     *
     * @param path File path to validate
     * @param baseDir Allowed base directory
     * @return Validation result
     */
    public ValidationResult validateFilePath(String path, String baseDir) {
        if (!isEnabled() || path == null || path.isEmpty()) {
            return ValidationResult.allowed();
        }

        // Check path traversal patterns
        for (GuardRule rule : compiledRules) {
            if ("path_traversal".equals(rule.id)) {
                if (rule.pattern.matcher(path).find()) {
                    log.warn("ToolGuard blocked file path with rule '{}': {}",
                            rule.id, rule.description);
                    return ValidationResult.blocked(rule.id, rule.description, rule.severity);
                }
            }
        }

        // Check if path is within base directory
        if (baseDir != null && !baseDir.isEmpty()) {
            java.nio.file.Path targetPath = java.nio.file.Path.of(path).normalize();
            java.nio.file.Path basePath = java.nio.file.Path.of(baseDir).normalize();
            if (!targetPath.startsWith(basePath)) {
                log.warn("ToolGuard blocked file path outside workspace: {}", path);
                return ValidationResult.blocked(
                        "path_escape",
                        "File path escapes allowed directory",
                        "HIGH"
                );
            }
        }

        return ValidationResult.allowed();
    }

    // Hook interface implementation

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!isEnabled()) {
            return Mono.just(event);
        }

        // Handle pre-acting events to intercept tool calls
        if (event instanceof PreActingEvent preActingEvent) {
            // TODO: Extract tool call info from event and validate
            // This requires access to tool call parameters from the event
            // For now, pass through - full implementation depends on AgentScope event structure
            log.debug("PreActingEvent intercepted for validation");
        }

        return Mono.just(event);
    }

    @Override
    public int priority() {
        return 100; // High priority for security hooks
    }

    /**
     * Internal guard rule representation.
     */
    private record GuardRule(String id, Pattern pattern, String description, String severity) {}

    /**
     * Validation result.
     */
    public static class ValidationResult {
        private final boolean allowed;
        private final String ruleId;
        private final String message;
        private final String severity;

        private ValidationResult(boolean allowed, String ruleId, String message, String severity) {
            this.allowed = allowed;
            this.ruleId = ruleId;
            this.message = message;
            this.severity = severity;
        }

        public static ValidationResult allowed() {
            return new ValidationResult(true, null, null, null);
        }

        public static ValidationResult blocked(String ruleId, String message, String severity) {
            return new ValidationResult(false, ruleId, message, severity);
        }

        public boolean isAllowed() {
            return allowed;
        }

        public String getRuleId() {
            return ruleId;
        }

        public String getMessage() {
            return message;
        }

        public String getSeverity() {
            return severity;
        }
    }
}
