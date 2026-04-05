package com.copaw.storage;

import com.copaw.model.skill.PoolSkillSpec;
import com.copaw.model.skill.SkillManifest;
import com.copaw.model.skill.SkillSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Store for managing skill pool.
 * Manages {data-dir}/skill_pool/ directory.
 */
@Component
public class SkillPoolStore {
    private static final Logger log = LoggerFactory.getLogger(SkillPoolStore.class);

    private static final String SKILL_MANIFEST_FILE = "skill.json";
    private static final String SKILL_MD_FILE = "SKILL.md";

    private final CoPawDataDir dataDir;
    private final JsonFileStore<Object> jsonFileStore;

    public SkillPoolStore(CoPawDataDir dataDir, JsonFileStore<Object> jsonFileStore) {
        this.dataDir = dataDir;
        this.jsonFileStore = jsonFileStore;
    }

    /**
     * Get the skill pool directory.
     *
     * @return the skill pool directory path
     */
    public Path getSkillPoolDir() {
        return dataDir.getSkillPoolDir();
    }

    /**
     * Load the skill pool manifest.
     *
     * @return the skill manifest, or an empty one if not found
     */
    public SkillManifest loadManifest() {
        Path manifestPath = getSkillPoolDir().resolve(SKILL_MANIFEST_FILE);
        if (!Files.exists(manifestPath)) {
            return SkillManifest.builder()
                    .schemaVersion("skill-pool-manifest.v1")
                    .build();
        }

        try {
            String content = Files.readString(manifestPath);
            return jsonFileStore.getObjectMapper().readValue(content, SkillManifest.class);
        } catch (IOException e) {
            log.warn("Failed to load skill pool manifest: {}", e.getMessage());
            return SkillManifest.builder()
                    .schemaVersion("skill-pool-manifest.v1")
                    .build();
        }
    }

    /**
     * Save the skill pool manifest.
     *
     * @param manifest the manifest to save
     */
    public void saveManifest(SkillManifest manifest) {
        Path manifestPath = getSkillPoolDir().resolve(SKILL_MANIFEST_FILE);
        try {
            Files.createDirectories(manifestPath.getParent());
            String json = jsonFileStore.getObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(manifest);
            Files.writeString(manifestPath, json);
        } catch (IOException e) {
            log.error("Failed to save skill pool manifest: {}", e.getMessage());
            throw new RuntimeException("Failed to save skill pool manifest", e);
        }
    }

    /**
     * List all skills in the pool.
     *
     * @return map of skill name to skill spec
     */
    public Map<String, SkillSpec> listSkills() {
        SkillManifest manifest = loadManifest();
        return manifest.getSkills();
    }

    /**
     * Get a specific skill from the pool.
     *
     * @param skillName the skill name
     * @return the skill spec, or null if not found
     */
    public SkillSpec getSkill(String skillName) {
        return listSkills().get(skillName);
    }

    /**
     * Check if a skill exists in the pool.
     *
     * @param skillName the skill name
     * @return true if the skill exists
     */
    public boolean skillExists(String skillName) {
        Path skillDir = getSkillPoolDir().resolve(skillName);
        return Files.exists(skillDir) && Files.exists(skillDir.resolve(SKILL_MD_FILE));
    }

    /**
     * Add a skill to the pool.
     *
     * @param skillSpec the skill spec to add
     */
    public void addSkill(SkillSpec skillSpec) {
        SkillManifest manifest = loadManifest();
        manifest.getSkills().put(skillSpec.getName(), skillSpec);
        saveManifest(manifest);
    }

    /**
     * Remove a skill from the pool.
     *
     * @param skillName the skill name to remove
     * @return true if removed successfully
     */
    public boolean removeSkill(String skillName) {
        SkillManifest manifest = loadManifest();
        SkillSpec removed = manifest.getSkills().remove(skillName);
        if (removed != null) {
            saveManifest(manifest);
            return true;
        }
        return false;
    }

    /**
     * Get the path to a skill's SKILL.md file.
     *
     * @param skillName the skill name
     * @return the path to SKILL.md
     */
    public Path getSkillMdPath(String skillName) {
        return getSkillPoolDir().resolve(skillName).resolve(SKILL_MD_FILE);
    }

    /**
     * Read the content of a skill's SKILL.md file.
     *
     * @param skillName the skill name
     * @return the content, or empty string if not found
     */
    public String readSkillContent(String skillName) {
        Path skillMdPath = getSkillMdPath(skillName);
        if (!Files.exists(skillMdPath)) {
            return "";
        }

        try {
            return Files.readString(skillMdPath);
        } catch (IOException e) {
            log.warn("Failed to read skill content for {}: {}", skillName, e.getMessage());
            return "";
        }
    }

    /**
     * Write content to a skill's SKILL.md file.
     *
     * @param skillName the skill name
     * @param content   the content to write
     */
    public void writeSkillContent(String skillName, String content) {
        Path skillMdPath = getSkillMdPath(skillName);
        try {
            Files.createDirectories(skillMdPath.getParent());
            Files.writeString(skillMdPath, content);
        } catch (IOException e) {
            log.error("Failed to write skill content for {}: {}", skillName, e.getMessage());
            throw new RuntimeException("Failed to write skill content", e);
        }
    }

    /**
     * Get the directory path for a skill.
     *
     * @param skillName the skill name
     * @return the skill directory path
     */
    public Path getSkillDir(String skillName) {
        return getSkillPoolDir().resolve(skillName);
    }

    /**
     * Create a new skill directory.
     *
     * @param skillName the skill name
     * @return the created directory path
     */
    public Path createSkillDir(String skillName) {
        Path skillDir = getSkillDir(skillName);
        try {
            Files.createDirectories(skillDir);
            return skillDir;
        } catch (IOException e) {
            log.error("Failed to create skill directory for {}: {}", skillName, e.getMessage());
            throw new RuntimeException("Failed to create skill directory", e);
        }
    }

    /**
     * Delete a skill directory.
     *
     * @param skillName the skill name
     * @return true if deleted successfully
     */
    public boolean deleteSkillDir(String skillName) {
        Path skillDir = getSkillDir(skillName);
        if (!Files.exists(skillDir)) {
            return false;
        }

        try {
            Files.walk(skillDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            log.warn("Failed to delete {}: {}", path, e.getMessage());
                        }
                    });
            return true;
        } catch (IOException e) {
            log.error("Failed to delete skill directory for {}: {}", skillName, e.getMessage());
            return false;
        }
    }
}
