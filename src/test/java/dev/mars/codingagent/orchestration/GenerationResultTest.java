package dev.mars.codingagent.orchestration;

import dev.mars.codingagent.orchestration.GenerationResult.ValidationReport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for GenerationResult record and its nested types.
 */
class GenerationResultTest {

    @Test
    void failed_createsFailedResult() {
        GenerationResult result = GenerationResult.failed("req-1", 3, "exhausted retries");

        assertThat(result.success()).isFalse();
        assertThat(result.requestId()).isEqualTo("req-1");
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(result.files()).isEmpty();
        assertThat(result.summary()).isEqualTo("exhausted retries");
        assertThat(result.completedAt()).isNotNull();
    }

    @Test
    void validationReport_empty_allFalse() {
        ValidationReport report = ValidationReport.empty();

        assertThat(report.lexicalValid()).isFalse();
        assertThat(report.compilationSuccess()).isFalse();
        assertThat(report.executionSuccess()).isFalse();
        assertThat(report.expectationsPass()).isFalse();
        assertThat(report.totalErrors()).isEqualTo(0);
        assertThat(report.issues()).isEmpty();
    }
}
