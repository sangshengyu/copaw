package cn.sangshy.sa.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Utility class for resolving SolutionArchitect data directory paths.
 *
 * <p>This class provides centralized access to various SolutionArchitect data directories
 * based on the configured data directory (default: ~/.sa).</p>
 */
@Component
public class SADataDir {

    private final Path dataDir;
    private final Path secretDir;

    public SADataDir(
            @Value("${sa.data-dir:#{null}}") String configuredDataDir,
            @Value("${sa.secret-dir:#{null}}") String configuredSecretDir) {
        // Resolve data directory
        if (configuredDataDir != null && !configuredDataDir.isEmpty()) {
            this.dataDir = resolvePath(configuredDataDir);
        } else {
            this.dataDir = Paths.get(System.getProperty("user.home"), ".sa");
        }

        // Resolve secret directory
        if (configuredSecretDir != null && !configuredSecretDir.isEmpty()) {
            this.secretDir = resolvePath(configuredSecretDir);
        } else {
            // Default: {dataDir}.secret
            this.secretDir = Paths.get(this.dataDir.toString() + ".secret");
        }
    }

    /**
     * Get the base data directory path.
     *
     * @return the data directory path
     */
    public Path getDataDir() {
        return dataDir;
    }

    /**
     * Get the secret directory path.
     *
     * @return the secret directory path
     */
    public Path getSecretDir() {
        return secretDir;
    }

    /**
     * Get the workspaces directory path (where all agent workspaces live).
     * This is the canonical parent directory for all agents, matching Python's
     * ~/.sa/workspaces/ structure.
     *
     * @return the workspaces directory path
     * @deprecated Use {@link #getWorkspacesDir()} instead. This method exists
     *             only for backward compatibility during migration.
     */
    @Deprecated
    public Path getAgentsDir() {
        return getWorkspacesDir();
    }

    /**
     * Get a specific agent's directory path.
     * Returns workspaces/{agentId} to match Python's layout.
     *
     * @param agentId the agent ID
     * @return the agent directory path
     */
    public Path getAgentDir(String agentId) {
        return getWorkspacesDir().resolve(agentId);
    }

    /**
     * Get the agent.json path for a specific agent.
     *
     * @param agentId the agent ID
     * @return the agent.json file path
     */
    public Path getAgentConfigPath(String agentId) {
        return getAgentDir(agentId).resolve("agent.json");
    }

    /**
     * Get the agent order file path.
     *
     * @return the agent order file path
     */
    public Path getAgentOrderPath() {
        return dataDir.resolve("agent_order.json");
    }

    /**
     * Get the skill_pool directory path.
     *
     * @return the skill_pool directory path
     */
    public Path getSkillPoolDir() {
        return dataDir.resolve("skill_pool");
    }

    /**
     * Get the providers directory path (in secret dir).
     *
     * @return the providers directory path
     */
    public Path getProvidersDir() {
        return secretDir.resolve("providers");
    }

    /**
     * Get the builtin providers directory path.
     *
     * @return the builtin providers directory path
     */
    public Path getBuiltinProvidersDir() {
        return getProvidersDir().resolve("builtin");
    }

    /**
     * Get the custom providers directory path.
     *
     * @return the custom providers directory path
     */
    public Path getCustomProvidersDir() {
        return getProvidersDir().resolve("custom");
    }

    /**
     * Get the providers.json path.
     *
     * @return the providers.json file path
     */
    public Path getProvidersConfigPath() {
        return secretDir.resolve("providers.json");
    }

    /**
     * Get the active_model.json path.
     *
     * @return the active_model.json file path
     */
    public Path getActiveLlmPath() {
        return secretDir.resolve("active_model.json");
    }

    /**
     * Get the envs.json path.
     *
     * @return the envs.json file path
     */
    public Path getEnvsPath() {
        return secretDir.resolve("envs.json");
    }

    /**
     * Get the config.json path.
     *
     * @return the config.json file path
     */
    public Path getConfigPath() {
        return dataDir.resolve("config.json");
    }

    /**
     * Get the token usage file path.
     *
     * @return the token usage file path
     */
    public Path getTokenUsagePath() {
        return dataDir.resolve("token_usage.json");
    }

    /**
     * Get the auth.json path.
     *
     * @return the auth.json file path
     */
    public Path getAuthPath() {
        return secretDir.resolve("auth.json");
    }

    /**
     * Get the sessions directory path for a specific agent.
     *
     * @param agentId the agent ID
     * @return the sessions directory path
     */
    public Path getSessionsDir(String agentId) {
        return getAgentDir(agentId).resolve("sessions");
    }

    /**
     * Get the chats.json path for a specific agent.
     *
     * @param agentId the agent ID
     * @return the chats.json file path
     */
    public Path getChatsPath(String agentId) {
        return getAgentDir(agentId).resolve("chats.json");
    }

    /**
     * Get the memory directory path for a specific agent.
     *
     * @param agentId the agent ID
     * @return the memory directory path
     */
    public Path getMemoryDir(String agentId) {
        return getAgentDir(agentId).resolve("memory");
    }

    /**
     * Get the workspaces directory path.
     *
     * @return the workspaces directory path
     */
    public Path getWorkspacesDir() {
        return dataDir.resolve("workspaces");
    }

    /**
     * Get a specific workspace directory path.
     *
     * @param workspaceId the workspace ID
     * @return the workspace directory path
     */
    public Path getWorkspaceDir(String workspaceId) {
        return getWorkspacesDir().resolve(workspaceId);
    }

    /**
     * Get the logs directory path.
     *
     * @return the logs directory path
     */
    public Path getLogsDir() {
        return dataDir.resolve("logs");
    }

    /**
     * Get the blocked history file path for skill scanner.
     *
     * @return the blocked history file path
     */
    public Path getBlockedHistoryPath() {
        return dataDir.resolve("skill_scanner_blocked.json");
    }

    /**
     * Resolve a path string, handling ~ expansion.
     *
     * @param pathStr the path string
     * @return the resolved Path
     */
    private Path resolvePath(String pathStr) {
        if (pathStr.startsWith("~")) {
            return Paths.get(System.getProperty("user.home"), pathStr.substring(2));
        }
        return Paths.get(pathStr);
    }
}
