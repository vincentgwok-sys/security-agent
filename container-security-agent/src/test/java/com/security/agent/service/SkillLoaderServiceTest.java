package com.security.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.security.agent.model.ExecutionContext;
import com.security.agent.model.EnvironmentFingerprint;
import com.security.agent.model.SkillDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SkillLoaderServiceTest {

    private SkillLoaderService skillLoader;
    private ObjectMapper mapper;

    @TempDir
    Path skillsDir;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        skillLoader = new SkillLoaderService(mapper);

        // Inject skillsDir path via reflection
        try {
            java.lang.reflect.Field f = SkillLoaderService.class.getDeclaredField("skillsDir");
            f.setAccessible(true);
            f.set(skillLoader, skillsDir.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("加载最新版本 Skill：同 skillId 多个文件取最大时间戳")
    void shouldLoadLatestVersion() throws Exception {
        // Write two versions of the same skill
        SkillDefinition v1 = createTestSkill("SEC-TEST-001", "Test Skill v1", 1000000000000L, 0);
        SkillDefinition v2 = createTestSkill("SEC-TEST-001", "Test Skill v2", 2000000000000L, 1);

        mapper.writeValue(skillsDir.resolve("SEC-TEST-001-1000000000000.json").toFile(), v1);
        mapper.writeValue(skillsDir.resolve("SEC-TEST-001-2000000000000.json").toFile(), v2);

        Map<String, SkillDefinition> result = skillLoader.loadLatestSkills();
        assertEquals(1, result.size());
        assertEquals("Test Skill v2", result.get("SEC-TEST-001").getSkillName());
        assertEquals(2000000000000L, result.get("SEC-TEST-001").getVersionTimestamp());
    }

    @Test
    @DisplayName("空目录返回空 map")
    void shouldReturnEmptyWhenNoSkills() {
        Map<String, SkillDefinition> result = skillLoader.loadLatestSkills();
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("格式非法文件跳过并记录错误")
    void shouldSkipInvalidFile() throws Exception {
        Files.writeString(skillsDir.resolve("bad-skill-1000000000000.json"), "{invalid json");
        Map<String, SkillDefinition> result = skillLoader.loadLatestSkills();
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("精确匹配：osType + osFlavor + requiredTools 全匹配")
    void shouldExactMatchContext() {
        SkillDefinition skill = createTestSkill("SEC-TEST-001", "Test", 1000000000000L, 0);

        // Add a Debian context
        ExecutionContext debianCtx = ExecutionContext.builder()
                .contextId("linux-debian")
                .environmentFingerprint(EnvironmentFingerprint.builder()
                        .osType("linux")
                        .osFlavors(List.of("debian", "ubuntu"))
                        .shellType("bash")
                        .requiredTools(List.of("cat", "grep", "mount"))
                        .build())
                .envCheckCommands(List.of("uname -s", "cat /etc/os-release"))
                .deprecated(false)
                .build();
        skill.setExecutionContexts(List.of(debianCtx));

        // Target matches Debian exactly
        EnvironmentFingerprint target = EnvironmentFingerprint.builder()
                .osType("linux")
                .osFlavors(List.of("debian"))
                .shellType("bash")
                .requiredTools(List.of("cat", "grep", "mount", "capsh"))
                .build();

        Optional<ExecutionContext> result = skillLoader.selectBestContext(skill, target);
        assertTrue(result.isPresent());
        assertEquals("linux-debian", result.get().getContextId());
    }

    @Test
    @DisplayName("模糊匹配：osType 相同但 flavor 不同")
    void shouldPartialMatchWhenFlavorDiffers() {
        SkillDefinition skill = createTestSkill("SEC-TEST-001", "Test", 1000000000000L, 0);

        ExecutionContext debianCtx = ExecutionContext.builder()
                .contextId("linux-debian")
                .environmentFingerprint(EnvironmentFingerprint.builder()
                        .osType("linux")
                        .osFlavors(List.of("debian"))
                        .shellType("bash")
                        .requiredTools(List.of("cat", "grep"))
                        .build())
                .deprecated(false)
                .build();
        skill.setExecutionContexts(List.of(debianCtx));

        // Target is CentOS
        EnvironmentFingerprint target = EnvironmentFingerprint.builder()
                .osType("linux")
                .osFlavors(List.of("centos"))
                .shellType("bash")
                .requiredTools(List.of("cat", "grep", "mount"))
                .build();

        Optional<ExecutionContext> result = skillLoader.selectBestContext(skill, target);
        assertTrue(result.isPresent());
        assertEquals("linux-debian", result.get().getContextId());
    }

    @Test
    @DisplayName("无匹配时返回 empty")
    void shouldReturnEmptyWhenNoMatch() {
        SkillDefinition skill = createTestSkill("SEC-TEST-001", "Test", 1000000000000L, 0);

        ExecutionContext linuxCtx = ExecutionContext.builder()
                .contextId("linux-only")
                .environmentFingerprint(EnvironmentFingerprint.builder()
                        .osType("linux")
                        .osFlavors(List.of("debian"))
                        .shellType("bash")
                        .requiredTools(List.of("cat"))
                        .build())
                .deprecated(false)
                .build();
        skill.setExecutionContexts(List.of(linuxCtx));

        // Target is Windows
        EnvironmentFingerprint target = EnvironmentFingerprint.builder()
                .osType("windows")
                .osFlavors(List.of("windows-container"))
                .shellType("powershell")
                .requiredTools(List.of("powershell"))
                .build();

        Optional<ExecutionContext> result = skillLoader.selectBestContext(skill, target);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("跳过已弃用的 deprecated Context")
    void shouldSkipDeprecatedContexts() {
        SkillDefinition skill = createTestSkill("SEC-TEST-001", "Test", 1000000000000L, 0);

        ExecutionContext deprecatedCtx = ExecutionContext.builder()
                .contextId("old-context")
                .environmentFingerprint(EnvironmentFingerprint.builder()
                        .osType("linux")
                        .osFlavors(List.of("debian"))
                        .requiredTools(List.of("cat"))
                        .build())
                .deprecated(true)
                .build();
        ExecutionContext activeCtx = ExecutionContext.builder()
                .contextId("new-context")
                .environmentFingerprint(EnvironmentFingerprint.builder()
                        .osType("linux")
                        .osFlavors(List.of("debian"))
                        .requiredTools(List.of("cat"))
                        .build())
                .deprecated(false)
                .build();
        skill.setExecutionContexts(List.of(deprecatedCtx, activeCtx));

        EnvironmentFingerprint target = EnvironmentFingerprint.builder()
                .osType("linux")
                .osFlavors(List.of("debian"))
                .requiredTools(List.of("cat"))
                .build();

        Optional<ExecutionContext> result = skillLoader.selectBestContext(skill, target);
        assertTrue(result.isPresent());
        assertEquals("new-context", result.get().getContextId());
    }

    @Test
    @DisplayName("进化持久化生成新文件并刷新缓存")
    void shouldSaveEvolvedSkill() {
        SkillDefinition skill = createTestSkill("SEC-TEST-001", "Test", 1000000000000L, 1);
        skillLoader.saveEvolvedSkill(skill);

        // Should have created a new file
        try {
            List<Path> files = Files.list(skillsDir)
                    .filter(p -> p.getFileName().toString().startsWith("SEC-TEST-001-"))
                    .toList();
            assertEquals(1, files.size());
            assertTrue(files.get(0).getFileName().toString().contains("SEC-TEST-001-"));
        } catch (Exception e) {
            fail("Failed to list skills dir", e);
        }
    }

    @Test
    @DisplayName("进化历史按时间戳降序排列")
    void shouldGetEvolutionHistoryDescending() throws Exception {
        SkillDefinition v1 = createTestSkill("SEC-TEST-001", "v1", 1000000000000L, 0);
        SkillDefinition v2 = createTestSkill("SEC-TEST-001", "v2", 2000000000000L, 1);
        SkillDefinition v3 = createTestSkill("SEC-TEST-001", "v3", 3000000000000L, 2);

        mapper.writeValue(skillsDir.resolve("SEC-TEST-001-1000000000000.json").toFile(), v1);
        mapper.writeValue(skillsDir.resolve("SEC-TEST-001-2000000000000.json").toFile(), v2);
        mapper.writeValue(skillsDir.resolve("SEC-TEST-001-3000000000000.json").toFile(), v3);

        List<SkillDefinition> history = skillLoader.getEvolutionHistory("SEC-TEST-001");
        assertEquals(3, history.size());
        assertEquals(3000000000000L, history.get(0).getVersionTimestamp());
        assertEquals(2000000000000L, history.get(1).getVersionTimestamp());
        assertEquals(1000000000000L, history.get(2).getVersionTimestamp());
    }

    private SkillDefinition createTestSkill(String skillId, String name, long timestamp, int evolutionCount) {
        return SkillDefinition.builder()
                .skillId(skillId)
                .skillName(name)
                .versionTimestamp(timestamp)
                .riskLevel("HIGH")
                .description("Test skill description")
                .evolutionCount(evolutionCount)
                .executionContexts(List.of(
                        ExecutionContext.builder()
                                .contextId("test-context")
                                .environmentFingerprint(EnvironmentFingerprint.builder()
                                        .osType("linux")
                                        .osFlavors(List.of("debian"))
                                        .shellType("bash")
                                        .requiredTools(List.of("cat"))
                                        .build())
                                .envCheckCommands(List.of("uname -s"))
                                .deprecated(false)
                                .build()))
                .build();
    }
}
