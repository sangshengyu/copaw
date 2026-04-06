package cn.sangshy.sa.controller;

import cn.sangshy.sa.model.common.EnvVar;
import cn.sangshy.sa.service.EnvService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Controller for managing environment variables.
 */
@RestController
@RequestMapping("/envs")
public class EnvsController {
    private static final Logger log = LoggerFactory.getLogger(EnvsController.class);

    private final EnvService envService;

    public EnvsController(EnvService envService) {
        this.envService = envService;
    }

    /**
     * List all environment variables.
     */
    @GetMapping("")
    public List<EnvVar> listEnvs() {
        return envService.listEnvVars();
    }

    /**
     * Batch save environment variables (full replacement).
     */
    @PutMapping("")
    public List<EnvVar> batchSaveEnvs(@RequestBody Map<String, String> body) {
        try {
            return envService.batchSaveEnvVars(body);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Delete an environment variable.
     */
    @DeleteMapping("/{key}")
    public List<EnvVar> deleteEnv(@PathVariable String key) {
        try {
            return envService.deleteEnvVar(key);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
