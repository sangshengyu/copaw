package com.copaw.controller;

import com.copaw.model.provider.ActiveModelsInfo;
import com.copaw.model.provider.ProviderInfo;
import com.copaw.model.provider.dto.*;
import com.copaw.service.ProviderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Controller for managing LLM providers and models.
 * Note: URL prefix is /models (not /providers) to match Python API.
 */
@RestController
@RequestMapping("/models")
public class ProvidersController {
    private static final Logger log = LoggerFactory.getLogger(ProvidersController.class);

    private final ProviderService providerService;

    public ProvidersController(ProviderService providerService) {
        this.providerService = providerService;
    }

    /**
     * List all providers.
     */
    @GetMapping("")
    public List<ProviderInfo> listProviders() {
        return providerService.listProviders();
    }

    /**
     * Configure a provider.
     */
    @PutMapping("/{providerId}/config")
    public ProviderInfo configureProvider(
            @PathVariable String providerId,
            @RequestBody ProviderConfigRequest request) {
        ProviderInfo provider = providerService.updateProvider(providerId, request);
        if (provider == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider '" + providerId + "' not found");
        }
        return provider;
    }

    /**
     * Create a custom provider.
     */
    @PostMapping("/custom-providers")
    public ResponseEntity<ProviderInfo> createCustomProvider(
            @RequestBody CreateCustomProviderRequest request) {
        ProviderInfo provider = providerService.addCustomProvider(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(provider);
    }

    /**
     * Delete a custom provider.
     */
    @DeleteMapping("/custom-providers/{providerId}")
    public List<ProviderInfo> deleteCustomProvider(@PathVariable String providerId) {
        boolean removed = providerService.removeCustomProvider(providerId);
        if (!removed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Custom Provider '" + providerId + "' not found");
        }
        return providerService.listProviders();
    }

    /**
     * Test provider connection.
     */
    @PostMapping("/{providerId}/test")
    public TestConnectionResponse testProvider(
            @PathVariable String providerId,
            @RequestBody(required = false) TestProviderRequest request) {
        return providerService.testProvider(providerId, request);
    }

    /**
     * Discover models from a provider.
     */
    @PostMapping("/{providerId}/discover")
    public DiscoverModelsResponse discoverModels(
            @PathVariable String providerId,
            @RequestBody(required = false) DiscoverModelsRequest request) {
        return providerService.discoverModels(providerId, request);
    }

    /**
     * Add a model to a provider.
     */
    @PostMapping("/{providerId}/models")
    public ResponseEntity<ProviderInfo> addModel(
            @PathVariable String providerId,
            @RequestBody AddModelRequest request) {
        try {
            ProviderInfo provider = providerService.addModel(providerId, request);
            return ResponseEntity.status(HttpStatus.CREATED).body(provider);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Remove a model from a provider.
     */
    @DeleteMapping("/{providerId}/models/{modelId}")
    public ProviderInfo removeModel(
            @PathVariable String providerId,
            @PathVariable String modelId) {
        try {
            return providerService.removeModel(providerId, modelId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Configure per-model generation parameters.
     */
    @PutMapping("/{providerId}/models/{modelId}/config")
    public ProviderInfo configureModel(
            @PathVariable String providerId,
            @PathVariable String modelId,
            @RequestBody ModelConfigRequest request) {
        try {
            return providerService.updateModelConfig(providerId, modelId, request);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Probe model multimodal capability.
     */
    @PostMapping("/{providerId}/models/{modelId}/probe-multimodal")
    public ProbeMultimodalResponse probeMultimodal(
            @PathVariable String providerId,
            @PathVariable String modelId) {
        try {
            return providerService.probeMultimodal(providerId, modelId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * Test a specific model.
     */
    @PostMapping("/{providerId}/models/test")
    public TestConnectionResponse testModel(
            @PathVariable String providerId,
            @RequestBody TestModelRequest request) {
        return providerService.testModel(providerId, request);
    }

    /**
     * Get effective active LLM.
     */
    @GetMapping("/active")
    public ActiveModelsInfo getActiveModels(
            @RequestParam(required = false, defaultValue = "effective") String scope,
            @RequestParam(required = false) String agentId) {
        // For now, only support "global" and "effective" scope
        // "agent" scope requires agent-specific config which is not implemented yet
        if ("agent".equals(scope)) {
            if (agentId == null || agentId.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agent_id is required when scope is 'agent'");
            }
            // TODO: Load agent-specific model config
            // For now, fall through to effective
        }
        return providerService.getActiveModels();
    }

    /**
     * Set active LLM.
     */
    @PutMapping("/active")
    public ActiveModelsInfo setActiveModel(@RequestBody ModelSlotRequest request) {
        if ("global".equals(request.getScope())) {
            try {
                return providerService.setActiveModel(request.getProviderId(), request.getModel());
            } catch (IllegalArgumentException e) {
                String msg = e.getMessage();
                if (msg != null && msg.toLowerCase().contains("provider") && msg.toLowerCase().contains("not found")) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
                }
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
            }
        }

        if ("agent".equals(request.getScope())) {
            if (request.getAgentId() == null || request.getAgentId().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "agent_id is required when scope is 'agent'");
            }
            // Validate model slot
            if (!providerService.hasModel(request.getProviderId(), request.getModel())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, 
                    "Model '" + request.getModel() + "' not found in provider '" + request.getProviderId() + "'");
            }
            // TODO: Save to agent-specific config
            // For now, just return the requested config
            return ActiveModelsInfo.builder()
                .activeLlm(com.copaw.model.provider.ModelSlotConfig.builder()
                    .providerId(request.getProviderId())
                    .model(request.getModel())
                    .build())
                .build();
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid scope: " + request.getScope());
    }
}
