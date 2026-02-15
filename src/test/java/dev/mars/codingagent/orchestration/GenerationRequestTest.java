package dev.mars.codingagent.orchestration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for GenerationRequest record.
 */
class GenerationRequestTest {

    @Test
    void of_basic_generatesRequestId() {
        GenerationRequest req = GenerationRequest.of("must validate amounts", "{\"amount\": 100}");

        assertThat(req.requestId()).startsWith("apex-");
        assertThat(req.requirements()).isEqualTo("must validate amounts");
        assertThat(req.dataStructure()).isEqualTo("{\"amount\": 100}");
        assertThat(req.hints()).isEmpty();
    }

    @Test
    void of_withHints_includesHints() {
        GenerationRequest req = GenerationRequest.of(
                "validate orders", "{\"orderId\": 1}",
                List.of("use rule-groups", "include enrichments"));

        assertThat(req.hints()).hasSize(2);
        assertThat(req.hints()).contains("use rule-groups", "include enrichments");
    }

    @Test
    void of_nullHints_defaultsToEmpty() {
        GenerationRequest req = GenerationRequest.of("req", "data", null);
        assertThat(req.hints()).isEmpty();
    }

    @Test
    void of_uniqueIds() {
        GenerationRequest r1 = GenerationRequest.of("req1", "data1");
        GenerationRequest r2 = GenerationRequest.of("req2", "data2");
        assertThat(r1.requestId()).isNotEqualTo(r2.requestId());
    }

    @Test
    void of_nullRequirements_throwsIllegalArgument() {
        assertThatThrownBy(() -> GenerationRequest.of(null, "data"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requirements");
    }

    @Test
    void of_blankRequirements_throwsIllegalArgument() {
        assertThatThrownBy(() -> GenerationRequest.of("  ", "data"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requirements");
    }

    @Test
    void of_withHints_nullRequirements_throwsIllegalArgument() {
        assertThatThrownBy(() -> GenerationRequest.of(null, "data", List.of("hint")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requirements");
    }

    @Test
    void of_nullDataStructure_defaultsToEmpty() {
        GenerationRequest req = GenerationRequest.of("some requirement", null);
        assertThat(req.dataStructure()).isEqualTo("");
    }
}
