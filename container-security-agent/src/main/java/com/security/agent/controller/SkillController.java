package com.security.agent.controller;

import com.security.agent.model.SkillDefinition;
import com.security.agent.service.SkillLoaderService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillLoaderService skillLoader;

    public SkillController(SkillLoaderService skillLoader) {
        this.skillLoader = skillLoader;
    }

    @GetMapping
    public ResponseEntity<?> listSkills() {
        Map<String, SkillDefinition> cache = skillLoader.getSkillCache();
        List<Map<String, Object>> result = cache.values().stream()
                .map(s -> Map.<String, Object>of(
                        "skillId", s.getSkillId(),
                        "skillName", s.getSkillName(),
                        "riskLevel", s.getRiskLevel() != null ? s.getRiskLevel() : "INFO",
                        "description", s.getDescription() != null ? s.getDescription() : "",
                        "evolutionCount", s.getEvolutionCount(),
                        "contextCount", s.getExecutionContexts() != null ? s.getExecutionContexts().size() : 0,
                        "versionTimestamp", s.getVersionTimestamp()))
                .sorted((a, b) -> ((String) a.get("skillName")).compareTo((String) b.get("skillName")))
                .toList();

        return ResponseEntity.ok(result);
    }

    @GetMapping("/{skillId}")
    public ResponseEntity<?> getSkill(@PathVariable String skillId) {
        SkillDefinition skill = skillLoader.getSkillCache().get(skillId);
        if (skill == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(skill);
    }

    @GetMapping("/{skillId}/contexts")
    public ResponseEntity<?> listContexts(@PathVariable String skillId) {
        SkillDefinition skill = skillLoader.getSkillCache().get(skillId);
        if (skill == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(skill.getExecutionContexts() != null
                ? skill.getExecutionContexts() : List.of());
    }

    @GetMapping("/{skillId}/contexts/{contextId}")
    public ResponseEntity<?> getContext(@PathVariable String skillId, @PathVariable String contextId) {
        SkillDefinition skill = skillLoader.getSkillCache().get(skillId);
        if (skill == null) {
            return ResponseEntity.notFound().build();
        }
        return skill.getExecutionContexts().stream()
                .filter(ctx -> contextId.equals(ctx.getContextId()))
                .findFirst()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{skillId}/history")
    public ResponseEntity<?> getHistory(@PathVariable String skillId) {
        List<SkillDefinition> history = skillLoader.getEvolutionHistory(skillId);
        return ResponseEntity.ok(history);
    }

    @PostMapping("/reload")
    public ResponseEntity<?> reloadSkills() {
        Map<String, SkillDefinition> skills = skillLoader.loadLatestSkills();
        return ResponseEntity.ok(Map.of(
                "message", "Skills 已重新加载",
                "count", skills.size()));
    }
}
