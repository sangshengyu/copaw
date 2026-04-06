package cn.sangshy.sa.model.skill;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Request to import builtin skills.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportBuiltinRequest {
    @JsonProperty("skill_names")
    @Builder.Default
    private List<String> skillNames = new ArrayList<>();

    @JsonProperty("overwrite_conflicts")
    @Builder.Default
    private Boolean overwriteConflicts = false;
}
