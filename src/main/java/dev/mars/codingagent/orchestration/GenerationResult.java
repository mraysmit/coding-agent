package dev.mars.codingagent.orchestration;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Output from the APEX generation pipeline.
 * Contains generated files, validation results, and metadata.
 */
public record GenerationResult(
        String requestId,
        boolean success,
        int attempts,
        List<GeneratedFile> files,
        ValidationReport validationReport,
        String summary,
        Path outputDirectory,
        Instant completedAt
) {

    /**
     * A single generated file (YAML config or JSON test data).
     */
    public record GeneratedFile(
            String fileName,
            String docType,
            String content,
            Path writtenTo
    ) {}

    /**
     * Validation report summarizing compile, execute, and expectation results.
     */
    public record ValidationReport(
            boolean lexicalValid,
            boolean compilationSuccess,
            boolean executionSuccess,
            boolean expectationsPass,
            int totalErrors,
            int totalWarnings,
            List<ValidationIssue> issues,
            Map<String, Object> executionDetails
    ) {
        public static ValidationReport empty() {
            return new ValidationReport(false, false, false, false, 0, 0, List.of(), Map.of());
        }
    }

    /**
     * A single validation issue with classification.
     */
    public record ValidationIssue(
            String errorCode,
            String message,
            String stage,
            String severity
    ) {}

    /**
     * Create a failed result with a reason.
     */
    public static GenerationResult failed(String requestId, int attempts, String reason) {
        return new GenerationResult(
                requestId, false, attempts, List.of(),
                ValidationReport.empty(), reason, null, Instant.now()
        );
    }
}
