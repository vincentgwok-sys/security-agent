package com.security.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import com.security.agent.model.CommandRule;
import com.security.agent.model.SkillDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Generates offline Python detection scripts by rendering Mustache templates
 * with embedded Skill definitions and command rules.
 */
@Service
public class ScriptGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ScriptGenerationService.class);
    private static final String SCRIPT_VERSION = "1.0.0";

    @Value("${security-agent.offline.upload-base-url:http://localhost:8080/api}")
    private String uploadBaseUrl;

    private final ObjectMapper objectMapper;
    private final SkillLoaderService skillLoader;
    private final CommandRuleService ruleService;
    private final MustacheFactory mf = new DefaultMustacheFactory();

    public ScriptGenerationService(ObjectMapper objectMapper,
                                   SkillLoaderService skillLoader,
                                   CommandRuleService ruleService) {
        this.objectMapper = objectMapper;
        this.skillLoader = skillLoader;
        this.ruleService = ruleService;
    }

    /**
     * Generate a Python detection script for the given skills.
     *
     * @param taskId   the detection task ID
     * @param skillIds the list of skill IDs to include
     * @return the generated Python script as a string
     */
    public String generateScript(String taskId, List<String> skillIds) {
        Map<String, SkillDefinition> skillsMap = skillLoader.loadLatestSkills();
        List<SkillDefinition> selectedSkills = new ArrayList<>();
        for (String skillId : skillIds) {
            SkillDefinition skill = skillsMap.get(skillId);
            if (skill != null) {
                selectedSkills.add(skill);
            } else {
                log.warn("Skill not found, skipping: {}", skillId);
            }
        }

        List<CommandRule> rules = ruleService.getAllRules();

        Map<String, Object> variables = new HashMap<>();
        variables.put("taskId", taskId);
        variables.put("scriptVersion", SCRIPT_VERSION);
        variables.put("generatedAt", LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        variables.put("skillsBase64", base64Encode(toJson(selectedSkills)));
        variables.put("rulesBase64", base64Encode(toJson(rules)));
        variables.put("uploadUrl", uploadBaseUrl);

        try {
            String templateContent = loadTemplate();
            Mustache mustache = mf.compile(
                    new StringReader(templateContent), "security-scan");
            StringWriter writer = new StringWriter();
            mustache.execute(writer, variables).flush();
            return writer.toString();
        } catch (Exception e) {
            log.error("脚本模板渲染失败", e);
            throw new RuntimeException("脚本模板渲染失败: " + e.getMessage(), e);
        }
    }

    private String loadTemplate() throws Exception {
        String resourcePath = "scripts/templates/security-scan.py.mustache";
        java.net.URL url = getClass().getClassLoader().getResource(resourcePath);
        if (url == null) {
            throw new IllegalArgumentException("脚本模板文件不存在: " + resourcePath);
        }
        Path path = Path.of(url.toURI());
        return Files.readString(path);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("JSON 序列化失败", e);
            return "[]";
        }
    }

    private String base64Encode(String json) {
        return Base64.getEncoder().encodeToString(
                json.getBytes(StandardCharsets.UTF_8));
    }
}
