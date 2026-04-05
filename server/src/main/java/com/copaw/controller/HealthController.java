package com.copaw.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    @Value("${copaw.version}")
    private String version;

    @GetMapping("/")
    public Map<String, Object> health() {
        return Map.of("version", version, "status", "running");
    }

    /**
     * Get version information.
     * Frontend calls GET /version to get the version.
     */
    @GetMapping("/version")
    public Map<String, String> getVersion() {
        return Map.of("version", version);
    }
}
