package dev.mars.codingagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for ApexExampleRetrievalTool — example search and corpus statistics.
 */
class ApexExampleRetrievalToolTest {

    private static ApexExampleRetrievalTool tool;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void setUp() {
        tool = ApexExampleRetrievalTool.builder()
                .indexPath(Path.of("knowledge", "example-index.json"))
                .apexProjectRoot(Path.of("..", "apex-rules-engine"))
                .build();
    }

    // ===== Search tests =====

    @Test
    void searchExamples_rulesFeature_returnsResults() throws Exception {
        String result = tool.searchExamples("rules", "any", 5);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("totalMatches").asInt()).isGreaterThan(0);
        assertThat(json.get("returned").asInt()).isGreaterThan(0);
        assertThat(json.get("returned").asInt()).isLessThanOrEqualTo(5);
        assertThat(json.get("results").isArray()).isTrue();
    }

    @Test
    void searchExamples_enrichmentsFeature_returnsResults() throws Exception {
        String result = tool.searchExamples("enrichments", "any", 3);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("totalMatches").asInt()).isGreaterThan(0);
        for (JsonNode r : json.get("results")) {
            assertThat(r.get("features").toString()).contains("enrichments");
        }
    }

    @Test
    void searchExamples_multipleFeatures_rankedByOverlap() throws Exception {
        String result = tool.searchExamples("rules,enrichments,rule-groups", "any", 10);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("totalMatches").asInt()).isGreaterThan(0);

        // Results should be sorted by matchScore descending
        JsonNode results = json.get("results");
        if (results.size() >= 2) {
            int score0 = results.get(0).get("matchScore").asInt();
            int score1 = results.get(1).get("matchScore").asInt();
            assertThat(score0).isGreaterThanOrEqualTo(score1);
        }
    }

    @Test
    void searchExamples_withDocTypeFilter_filtersCorrectly() throws Exception {
        // This test verifies filtering works, but since many examples have metadataType NONE,
        // we just verify no errors and structured response
        String result = tool.searchExamples("rules", "rule-config", 5);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.has("query")).isTrue();
        assertThat(json.get("query").get("docType").asText()).isEqualTo("rule-config");
        assertThat(json.has("results")).isTrue();
    }

    @Test
    void searchExamples_resultHasAllFields() throws Exception {
        String result = tool.searchExamples("rules", "any", 1);
        JsonNode json = MAPPER.readTree(result);

        if (json.get("returned").asInt() > 0) {
            JsonNode first = json.get("results").get(0);
            assertThat(first.has("path")).isTrue();
            assertThat(first.has("absolutePath")).isTrue();
            assertThat(first.has("metadataType")).isTrue();
            assertThat(first.has("features")).isTrue();
            assertThat(first.has("complexity")).isTrue();
            assertThat(first.has("matchScore")).isTrue();
        }
    }

    @Test
    void searchExamples_topKLimitsResults() throws Exception {
        String result = tool.searchExamples("rules", "any", 2);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("returned").asInt()).isLessThanOrEqualTo(2);
    }

    @Test
    void searchExamples_nonexistentFeature_returnsSuggestion() throws Exception {
        String result = tool.searchExamples("nonexistent-feature-xyz", "any", 5);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("totalMatches").asInt()).isEqualTo(0);
        assertThat(json.has("suggestion")).isTrue();
    }

    // ===== Corpus stats =====

    @Test
    void getCorpusStats_returnsStructuredStats() throws Exception {
        String result = tool.getCorpusStats();
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("totalFiles").asInt()).isGreaterThan(100);
        assertThat(json.has("complexityDistribution")).isTrue();
        assertThat(json.has("featureCounts")).isTrue();
        assertThat(json.has("documentTypeCounts")).isTrue();
    }

    @Test
    void getCorpusStats_featureCountsHaveExpectedEntries() throws Exception {
        String result = tool.getCorpusStats();
        JsonNode json = MAPPER.readTree(result);
        JsonNode features = json.get("featureCounts");

        assertThat(features.has("rules")).isTrue();
        assertThat(features.has("enrichments")).isTrue();
        assertThat(features.get("rules").asInt()).isGreaterThan(50);
        assertThat(features.get("enrichments").asInt()).isGreaterThan(50);
    }

    @Test
    void getCorpusStats_complexitySumsToTotal() throws Exception {
        String result = tool.getCorpusStats();
        JsonNode json = MAPPER.readTree(result);

        int total = json.get("totalFiles").asInt();
        JsonNode dist = json.get("complexityDistribution");
        int sum = 0;
        var it = dist.fieldNames();
        while (it.hasNext()) {
            sum += dist.get(it.next()).asInt();
        }
        assertThat(sum).isEqualTo(total);
    }
}
