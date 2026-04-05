package com.copaw.controller;

import com.copaw.model.common.TokenUsageSummary;
import com.copaw.service.TokenUsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * Controller for token usage statistics.
 */
@RestController
@RequestMapping("/token-usage")
public class TokenUsageController {
    private static final Logger log = LoggerFactory.getLogger(TokenUsageController.class);

    private final TokenUsageService tokenUsageService;

    public TokenUsageController(TokenUsageService tokenUsageService) {
        this.tokenUsageService = tokenUsageService;
    }

    /**
     * Get token usage summary for a date range.
     *
     * @param startDate start date (YYYY-MM-DD), defaults to 30 days ago
     * @param endDate   end date (YYYY-MM-DD), defaults to today
     * @param model     filter by model name
     * @param provider  filter by provider ID
     * @return the token usage summary
     */
    @GetMapping("")
    public TokenUsageSummary getTokenUsage(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String provider) {
        
        return tokenUsageService.getSummary(startDate, endDate, model, provider);
    }
}
