package com.copaw.model.provider.dto;

import com.copaw.model.provider.ModelInfo;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Response for discovering models from a provider.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DiscoverModelsResponse {
    @JsonProperty("success")
    private Boolean success;

    @JsonProperty("models")
    @Builder.Default
    private List<ModelInfo> models = new ArrayList<>();

    @JsonProperty("message")
    @Builder.Default
    private String message = "";

    @JsonProperty("added_count")
    @Builder.Default
    private Integer addedCount = 0;
}
