package dev.mars.apexaiagent.orchestration;

import java.time.Instant;
import java.util.List;

/**
 * Output from the APEX description pipeline.
 * Contains a human-readable business description derived from
 * an APEX YAML configuration and optional sample data.
 */
public record DescriptionResult(
        String requestId,
        boolean success,
        String description,
        String summary,
        List<RuleDescription> rules,
        List<String> dataFieldsIdentified,
        String error,
        Instant completedAt
) {
    /**
     * Description of a single APEX rule in business terms.
     */
    public record RuleDescription(
            String ruleId,
            String ruleName,
            String businessMeaning,
            String condition,
            String severity,
            List<String> exampleOutcomes
    ) {}

    /**
     * Create a successful result.
     */
    public static DescriptionResult success(
            String requestId,
            String description,
            String summary,
            List<RuleDescription> rules,
            List<String> dataFieldsIdentified) {
        return new DescriptionResult(
                requestId, true, description, summary,
                rules != null ? rules : List.of(),
                dataFieldsIdentified != null ? dataFieldsIdentified : List.of(),
                null,
                Instant.now()
        );
    }

    /**
     * Create a failed result.
     */
    public static DescriptionResult failed(String requestId, String reason) {
        return new DescriptionResult(
                requestId, false, null, null, List.of(), List.of(), reason, Instant.now()
        );
    }
}
