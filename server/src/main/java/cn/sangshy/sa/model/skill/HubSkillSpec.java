package cn.sangshy.sa.model.skill;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Hub skill specification for search results.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HubSkillSpec {
    @JsonProperty("slug")
    private String slug;

    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    @Builder.Default
    private String description = "";

    @JsonProperty("version")
    @Builder.Default
    private String version = "";

    @JsonProperty("source_url")
    @Builder.Default
    private String sourceUrl = "";
}
