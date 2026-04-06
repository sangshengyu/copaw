package cn.sangshy.sa.service;

import cn.sangshy.sa.model.common.TokenUsageSummary;
import cn.sangshy.sa.storage.TokenUsageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Service for managing token usage statistics.
 */
@Service
public class TokenUsageService {
    private static final Logger log = LoggerFactory.getLogger(TokenUsageService.class);

    private final TokenUsageStore tokenUsageStore;

    public TokenUsageService(TokenUsageStore tokenUsageStore) {
        this.tokenUsageStore = tokenUsageStore;
    }

    /**
     * Get token usage summary for a date range.
     *
     * @param startDate start date (inclusive), null for 30 days ago
     * @param endDate   end date (inclusive), null for today
     * @param model     filter by model name, null for all
     * @param provider  filter by provider ID, null for all
     * @return the token usage summary
     */
    public TokenUsageSummary getSummary(LocalDate startDate, LocalDate endDate, String model, String provider) {
        // Default date range: last 30 days
        LocalDate end = endDate != null ? endDate : LocalDate.now();
        LocalDate start = startDate != null ? startDate : end.minusDays(30);
        
        // Ensure start <= end
        if (start.isAfter(end)) {
            LocalDate temp = start;
            start = end;
            end = temp;
        }
        
        TokenUsageSummary summary = tokenUsageStore.getSummary(start, end);
        
        // Apply filters if specified
        if (model != null && !model.isEmpty()) {
            summary = filterByModel(summary, model);
        }
        
        if (provider != null && !provider.isEmpty()) {
            summary = filterByProvider(summary, provider);
        }
        
        return summary;
    }

    /**
     * Record token usage.
     *
     * @param providerId       the provider ID
     * @param modelName        the model name
     * @param promptTokens     prompt tokens used
     * @param completionTokens completion tokens used
     */
    public void record(String providerId, String modelName, long promptTokens, long completionTokens) {
        tokenUsageStore.record(providerId, modelName, promptTokens, completionTokens, LocalDate.now());
    }

    private TokenUsageSummary filterByModel(TokenUsageSummary summary, String modelFilter) {
        // Create a new summary with filtered data
        TokenUsageSummary filtered = TokenUsageSummary.builder()
            .totalPromptTokens(0L)
            .totalCompletionTokens(0L)
            .totalCalls(0L)
            .byModel(new java.util.HashMap<>())
            .byProvider(new java.util.HashMap<>())
            .byDate(new java.util.HashMap<>())
            .build();
        
        // Filter by_model entries
        summary.getByModel().forEach((key, value) -> {
            if (value.getModel() != null && value.getModel().contains(modelFilter)) {
                filtered.getByModel().put(key, value);
                filtered.setTotalPromptTokens(filtered.getTotalPromptTokens() + value.getPromptTokens());
                filtered.setTotalCompletionTokens(filtered.getTotalCompletionTokens() + value.getCompletionTokens());
                filtered.setTotalCalls(filtered.getTotalCalls() + value.getCallCount());
            }
        });
        
        return filtered;
    }

    private TokenUsageSummary filterByProvider(TokenUsageSummary summary, String providerFilter) {
        // Create a new summary with filtered data
        TokenUsageSummary filtered = TokenUsageSummary.builder()
            .totalPromptTokens(0L)
            .totalCompletionTokens(0L)
            .totalCalls(0L)
            .byModel(new java.util.HashMap<>())
            .byProvider(new java.util.HashMap<>())
            .byDate(new java.util.HashMap<>())
        .build();
        
        // Filter by_provider entries
        summary.getByProvider().forEach((key, value) -> {
            if (key.equals(providerFilter)) {
                filtered.getByProvider().put(key, value);
                filtered.setTotalPromptTokens(filtered.getTotalPromptTokens() + value.getPromptTokens());
                filtered.setTotalCompletionTokens(filtered.getTotalCompletionTokens() + value.getCompletionTokens());
                filtered.setTotalCalls(filtered.getTotalCalls() + value.getCallCount());
            }
        });
        
        return filtered;
    }
}
