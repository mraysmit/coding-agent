package dev.mars.apexaiagent.orchestration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Input to the APEX generation pipeline.
 * Contains the business requirements and optional data structures.
 */
public record GenerationRequest(
        String requestId,
        String requirements,
        String dataStructure,
        List<String> hints
) {
    /**
     * Create a request with auto-generated ID.
     */
    public static GenerationRequest of(String requirements, String dataStructure) {
        if (requirements == null || requirements.isBlank()) {
            throw new IllegalArgumentException("requirements must not be null or blank");
        }
        return new GenerationRequest(
                "apex-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8),
                requirements,
                dataStructure != null ? dataStructure : "",
                List.of()
        );
    }

    /**
     * Create a request with hints for the agent.
     */
    public static GenerationRequest of(String requirements, String dataStructure, List<String> hints) {
        if (requirements == null || requirements.isBlank()) {
            throw new IllegalArgumentException("requirements must not be null or blank");
        }
        return new GenerationRequest(
                "apex-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8),
                requirements,
                dataStructure != null ? dataStructure : "",
                hints != null ? hints : List.of()
        );
    }
}
