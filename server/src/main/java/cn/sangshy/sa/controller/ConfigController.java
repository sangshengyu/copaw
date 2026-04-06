package cn.sangshy.sa.controller;

import cn.sangshy.sa.model.config.*;
import cn.sangshy.sa.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Controller for managing global configuration.
 */
@RestController
@RequestMapping("/config")
public class ConfigController {
    private static final Logger log = LoggerFactory.getLogger(ConfigController.class);

    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
    }

    // ── Agents LLM Routing ──────────────────────────────────────────────────

    @GetMapping("/agents/llm-routing")
    public AgentsLLMRoutingConfig getAgentsLLMRouting() {
        return configService.getAgentsLLMRouting();
    }

    @PutMapping("/agents/llm-routing")
    public AgentsLLMRoutingConfig putAgentsLLMRouting(@RequestBody AgentsLLMRoutingConfig body) {
        return configService.updateAgentsLLMRouting(body);
    }

    // ── User Timezone ────────────────────────────────────────────────────

    @GetMapping("/user-timezone")
    public Map<String, String> getUserTimezone() {
        return Map.of("timezone", configService.getUserTimezone());
    }

    @PutMapping("/user-timezone")
    public Map<String, String> putUserTimezone(@RequestBody Map<String, String> body) {
        String tz = body.get("timezone");
        if (tz == null || tz.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "timezone is required");
        }
        return Map.of("timezone", configService.updateUserTimezone(tz.trim()));
    }

    // ── Security / Tool Guard ────────────────────────────────────────────

    @GetMapping("/security/tool-guard")
    public ToolGuardConfig getToolGuard() {
        return configService.getToolGuard();
    }

    @PutMapping("/security/tool-guard")
    public ToolGuardConfig putToolGuard(@RequestBody ToolGuardConfig body) {
        return configService.updateToolGuard(body);
    }

    @GetMapping("/security/tool-guard/builtin-rules")
    public List<ToolGuardRuleConfig> getBuiltinToolGuardRules() {
        return configService.getBuiltinToolGuardRules();
    }

    // ── Security / File Guard ────────────────────────────────────────────

    @GetMapping("/security/file-guard")
    public FileGuardResponse getFileGuard() {
        return configService.getFileGuard();
    }

    @PutMapping("/security/file-guard")
    public FileGuardResponse putFileGuard(@RequestBody FileGuardUpdateBody body) {
        return configService.updateFileGuard(body);
    }

    // ── Security / Skill Scanner ────────────────────────────────────────

    @GetMapping("/security/skill-scanner")
    public SkillScannerConfig getSkillScanner() {
        return configService.getSkillScanner();
    }

    @PutMapping("/security/skill-scanner")
    public SkillScannerConfig putSkillScanner(@RequestBody SkillScannerConfig body) {
        return configService.updateSkillScanner(body);
    }

    @GetMapping("/security/skill-scanner/blocked-history")
    public List<Map<String, Object>> getBlockedHistory() {
        return configService.getBlockedHistory();
    }

    @DeleteMapping("/security/skill-scanner/blocked-history")
    public Map<String, Boolean> clearBlockedHistory() {
        boolean cleared = configService.clearBlockedHistory();
        return Map.of("cleared", cleared);
    }

    @DeleteMapping("/security/skill-scanner/blocked-history/{index}")
    public Map<String, Boolean> removeBlockedEntry(@PathVariable int index) {
        boolean removed = configService.removeBlockedEntry(index);
        if (!removed) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Entry not found");
        }
        return Map.of("removed", true);
    }

    @PostMapping("/security/skill-scanner/whitelist")
    public Map<String, Object> addToWhitelist(@RequestBody WhitelistAddRequest body) {
        String skillName = body.getSkillName();
        if (skillName == null || skillName.trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "skill_name is required");
        }
        try {
            return configService.addToWhitelist(skillName.trim(), body.getContentHash());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @DeleteMapping("/security/skill-scanner/whitelist/{skillName}")
    public Map<String, Object> removeFromWhitelist(@PathVariable String skillName) {
        try {
            return configService.removeFromWhitelist(skillName);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
