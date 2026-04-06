package cn.sangshy.sa.security;

/**
 * Exception thrown when ToolGuard blocks a tool execution.
 */
public class ToolGuardException extends RuntimeException {

    private final String ruleId;
    private final String severity;

    /**
     * Creates a new ToolGuardException.
     *
     * @param message  The error message
     * @param ruleId   The ID of the rule that triggered the block
     * @param severity The severity level of the violation
     */
    public ToolGuardException(String message, String ruleId, String severity) {
        super(message);
        this.ruleId = ruleId;
        this.severity = severity;
    }

    /**
     * Gets the rule ID that triggered the block.
     *
     * @return The rule ID
     */
    public String getRuleId() {
        return ruleId;
    }

    /**
     * Gets the severity level of the violation.
     *
     * @return The severity level
     */
    public String getSeverity() {
        return severity;
    }
}
