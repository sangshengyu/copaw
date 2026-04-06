package cn.sangshy.sa.storage;

import cn.sangshy.sa.model.common.TokenUsageStats;
import cn.sangshy.sa.model.common.TokenUsageSummary;
import cn.sangshy.sa.model.common.TokenUsageByModel;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Store for managing token usage data.
 * Reads and writes token_usage.json.
 */
@Component
public class TokenUsageStore {
    private static final Logger log = LoggerFactory.getLogger(TokenUsageStore.class);

    private final SADataDir dataDir;
    private final JsonFileStore<Object> jsonFileStore;

    public TokenUsageStore(SADataDir dataDir, JsonFileStore<Object> jsonFileStore) {
        this.dataDir = dataDir;
        this.jsonFileStore = jsonFileStore;
    }

    /**
     * Load all token usage data.
     *
     * @return map of date -> (composite_key -> usage data)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> loadData() {
        Path usagePath = dataDir.getTokenUsagePath();
        if (!Files.exists(usagePath)) {
            return new HashMap<>();
        }

        try {
            String content = Files.readString(usagePath);
            return jsonFileStore.getObjectMapper().readValue(content, Map.class);
        } catch (IOException e) {
            log.warn("Failed to load token usage data: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Save all token usage data.
     *
     * @param data the token usage data to save
     */
    public void saveData(Map<String, Map<String, Object>> data) {
        Path usagePath = dataDir.getTokenUsagePath();
        try {
            Files.createDirectories(usagePath.getParent());
            String json = jsonFileStore.getObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsString(data);
            Files.writeString(usagePath, json);
        } catch (IOException e) {
            log.error("Failed to save token usage data: {}", e.getMessage());
            throw new RuntimeException("Failed to save token usage data", e);
        }
    }

    /**
     * Record token usage for a specific provider/model/date.
     *
     * @param providerId       the provider ID
     * @param modelName        the model name
     * @param promptTokens     prompt tokens used
     * @param completionTokens completion tokens used
     * @param date             the date (or null for today)
     */
    public void record(String providerId, String modelName, 
                       long promptTokens, long completionTokens, LocalDate date) {
        if (date == null) {
            date = LocalDate.now();
        }

        String dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String compositeKey = providerId + ":" + modelName;

        Map<String, Map<String, Object>> data = loadData();
        
        Map<String, Object> dayData = data.computeIfAbsent(dateStr, k -> new HashMap<>());
        Map<String, Object> entry = (Map<String, Object>) dayData.get(compositeKey);
        
        if (entry == null) {
            entry = new HashMap<>();
            entry.put("provider_id", providerId);
            entry.put("model_name", modelName);
            entry.put("prompt_tokens", 0L);
            entry.put("completion_tokens", 0L);
            entry.put("call_count", 0L);
            dayData.put(compositeKey, entry);
        }

        entry.put("prompt_tokens", ((Number) entry.get("prompt_tokens")).longValue() + promptTokens);
        entry.put("completion_tokens", ((Number) entry.get("completion_tokens")).longValue() + completionTokens);
        entry.put("call_count", ((Number) entry.get("call_count")).longValue() + 1);

        saveData(data);
    }

    /**
     * Get token usage summary for a date range.
     *
     * @param startDate start date (inclusive)
     * @param endDate   end date (inclusive)
     * @return the token usage summary
     */
    public TokenUsageSummary getSummary(LocalDate startDate, LocalDate endDate) {
        Map<String, Map<String, Object>> data = loadData();
        
        long totalPrompt = 0;
        long totalCompletion = 0;
        long totalCalls = 0;
        Map<String, TokenUsageByModel> byModel = new HashMap<>();
        Map<String, TokenUsageStats> byProvider = new HashMap<>();
        Map<String, TokenUsageStats> byDate = new HashMap<>();

        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            String dateStr = current.format(DateTimeFormatter.ISO_LOCAL_DATE);
            Map<String, Object> dayData = data.get(dateStr);
            
            if (dayData != null) {
                long dayPrompt = 0;
                long dayCompletion = 0;
                long dayCalls = 0;

                for (Map.Entry<String, Object> e : dayData.entrySet()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> entry = (Map<String, Object>) e.getValue();
                    
                    long pt = ((Number) entry.get("prompt_tokens")).longValue();
                    long ct = ((Number) entry.get("completion_tokens")).longValue();
                    long cc = ((Number) entry.get("call_count")).longValue();
                    String providerId = (String) entry.get("provider_id");
                    String model = (String) entry.get("model_name");
                    
                    totalPrompt += pt;
                    totalCompletion += ct;
                    totalCalls += cc;
                    dayPrompt += pt;
                    dayCompletion += ct;
                    dayCalls += cc;

                    // By model
                    String modelKey = providerId + ":" + model;
                    TokenUsageByModel modelStats = byModel.computeIfAbsent(modelKey, 
                            k -> TokenUsageByModel.builder()
                                    .providerId(providerId)
                                    .model(model)
                                    .build());
                    modelStats.setPromptTokens(modelStats.getPromptTokens() + pt);
                    modelStats.setCompletionTokens(modelStats.getCompletionTokens() + ct);
                    modelStats.setCallCount(modelStats.getCallCount() + cc);

                    // By provider
                    TokenUsageStats providerStats = byProvider.computeIfAbsent(providerId,
                            k -> TokenUsageStats.builder().build());
                    providerStats.setPromptTokens(providerStats.getPromptTokens() + pt);
                    providerStats.setCompletionTokens(providerStats.getCompletionTokens() + ct);
                    providerStats.setCallCount(providerStats.getCallCount() + cc);
                }

                // By date
                TokenUsageStats dateStats = TokenUsageStats.builder()
                        .promptTokens(dayPrompt)
                        .completionTokens(dayCompletion)
                        .callCount(dayCalls)
                        .build();
                byDate.put(dateStr, dateStats);
            }
            
            current = current.plusDays(1);
        }

        return TokenUsageSummary.builder()
                .totalPromptTokens(totalPrompt)
                .totalCompletionTokens(totalCompletion)
                .totalCalls(totalCalls)
                .byModel(byModel)
                .byProvider(byProvider)
                .byDate(byDate)
                .build();
    }
}
