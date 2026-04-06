package cn.sangshy.sa.service;

import cn.sangshy.sa.model.skill.PoolSkillSpec;
import cn.sangshy.sa.model.skill.SkillManifest;
import cn.sangshy.sa.model.skill.SkillSpec;
import cn.sangshy.sa.storage.AgentConfigStore;
import cn.sangshy.sa.storage.SADataDir;
import cn.sangshy.sa.storage.JsonFileStore;
import cn.sangshy.sa.storage.SkillPoolStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for skill pool initialization and reconciliation.
 * Verifies:
 * - Pool initialization imports builtin skills
 * - reconcilePoolManifest scans disk and rebuilds metadata
 * - listPoolSkills returns pool contents
 */
class SkillPoolTest {

    @TempDir
    Path tempDir;

    private SkillService skillService;
    private SkillPoolStore skillPoolStore;
    private SADataDir dataDir;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        dataDir = mock(SADataDir.class);
        when(dataDir.getSkillPoolDir()).thenReturn(tempDir.resolve("skill_pool"));
        when(dataDir.getDataDir()).thenReturn(tempDir);

        objectMapper = new ObjectMapper();
        JsonFileStore<Object> jsonFileStore = new JsonFileStore<>();
        skillPoolStore = new SkillPoolStore(dataDir, jsonFileStore);

        AgentConfigStore agentConfigStore = mock(AgentConfigStore.class);
        skillService = new SkillService(dataDir, skillPoolStore, agentConfigStore, objectMapper);
    }

    @Test
    void ensureSkillPoolInitialized_shouldCreatePoolDirectory() {
        // When: Initialize skill pool
        skillService.ensureSkillPoolInitialized();

        // Then: Pool directory should exist
        Path poolDir = tempDir.resolve("skill_pool");
        assertThat(poolDir).exists().isDirectory();

        // And: Manifest file should exist
        Path manifestPath = poolDir.resolve("skill.json");
        assertThat(manifestPath).exists();
    }

    @Test
    void ensureSkillPoolInitialized_shouldImportBuiltinSkills() {
        // When: Initialize skill pool
        skillService.ensureSkillPoolInitialized();

        // Then: Pool should contain builtin skills extracted from classpath
        List<PoolSkillSpec> poolSkills = skillService.listPoolSkills();
        SkillManifest manifest = skillPoolStore.loadManifest();
        assertThat(manifest).isNotNull();
        assertThat(manifest.getSchemaVersion()).isEqualTo("skill-pool-manifest.v1");

        // Builtin skills from classpath should be imported into the pool
        // The 6 builtin skills: browser_cdp, browser_visible, sa_source_index, file_reader, guidance, pdf
        assertThat(poolSkills).isNotEmpty();
        assertThat(poolSkills).hasSize(6);
        List<String> names = poolSkills.stream().map(PoolSkillSpec::getName).toList();
        assertThat(names).contains("browser_cdp", "guidance", "pdf");

        // Verify skill directories exist in pool
        Path poolDir = tempDir.resolve("skill_pool");
        assertThat(poolDir.resolve("browser_cdp").resolve("SKILL.md")).exists();
        assertThat(poolDir.resolve("pdf").resolve("SKILL.md")).exists();
    }

    @Test
    void reconcilePoolManifest_shouldDiscoverSkillsOnDisk() throws IOException {
        // Given: A skill directory with SKILL.md
        Path poolDir = tempDir.resolve("skill_pool");
        Files.createDirectories(poolDir);
        Path skillDir = poolDir.resolve("test_skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\ndescription: Test skill\nversion: 1.0\n---\n# Test");

        // And: An initial empty manifest
        SkillManifest emptyManifest = SkillManifest.builder()
                .schemaVersion("skill-pool-manifest.v1")
                .build();
        skillPoolStore.saveManifest(emptyManifest);

        // When: Reconcile
        List<PoolSkillSpec> result = skillService.reconcilePoolManifest();

        // Then: The skill should be discovered and added to manifest
        assertThat(result).isNotEmpty();
        assertThat(result).anyMatch(s -> "test_skill".equals(s.getName()));
    }

    @Test
    void reconcilePoolManifest_shouldRemoveDeletedSkills() throws IOException {
        // Given: Pool dir with manifest referencing a deleted skill
        Path poolDir = tempDir.resolve("skill_pool");
        Files.createDirectories(poolDir);

        SkillManifest manifest = SkillManifest.builder()
                .schemaVersion("skill-pool-manifest.v1")
                .build();
        manifest.getSkills().put("deleted_skill", SkillSpec.builder()
                .name("deleted_skill")
                .source("customized")
                .build());
        skillPoolStore.saveManifest(manifest);

        // When: Reconcile
        List<PoolSkillSpec> result = skillService.reconcilePoolManifest();

        // Then: Deleted skill should be removed
        assertThat(result).noneMatch(s -> "deleted_skill".equals(s.getName()));
    }

    @Test
    void listPoolSkills_shouldReturnSortedByName() throws IOException {
        // Given: Multiple skills in pool
        Path poolDir = tempDir.resolve("skill_pool");
        Files.createDirectories(poolDir);

        for (String name : List.of("zebra", "apple", "mango")) {
            Path skillDir = poolDir.resolve(name);
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), "---\ndescription: " + name + "\n---\n# " + name);
        }

        // Initialize manifest via reconcile
        SkillManifest emptyManifest = SkillManifest.builder()
                .schemaVersion("skill-pool-manifest.v1")
                .build();
        skillPoolStore.saveManifest(emptyManifest);
        skillService.reconcilePoolManifest();

        // When: List pool skills
        List<PoolSkillSpec> skills = skillService.listPoolSkills();

        // Then: Should be sorted by name
        assertThat(skills).hasSize(3);
        assertThat(skills.get(0).getName()).isEqualTo("apple");
        assertThat(skills.get(1).getName()).isEqualTo("mango");
        assertThat(skills.get(2).getName()).isEqualTo("zebra");
    }

    @Test
    void reconcilePoolManifest_shouldPreserveExistingConfig() throws IOException {
        // Given: A skill with config in the manifest
        Path poolDir = tempDir.resolve("skill_pool");
        Files.createDirectories(poolDir);

        Path skillDir = poolDir.resolve("configured_skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\ndescription: Configured\n---\n# Configured");

        SkillManifest manifest = SkillManifest.builder()
                .schemaVersion("skill-pool-manifest.v1")
                .build();
        SkillSpec spec = SkillSpec.builder()
                .name("configured_skill")
                .source("customized")
                .build();
        manifest.getSkills().put("configured_skill", spec);
        skillPoolStore.saveManifest(manifest);

        // When: Reconcile
        skillService.reconcilePoolManifest();

        // Then: The skill should still exist in pool
        SkillManifest updated = skillPoolStore.loadManifest();
        assertThat(updated.getSkills()).containsKey("configured_skill");
        assertThat(updated.getSkills().get("configured_skill").getSource()).isEqualTo("customized");
    }
}
