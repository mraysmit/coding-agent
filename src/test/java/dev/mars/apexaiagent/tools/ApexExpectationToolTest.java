package dev.mars.apexaiagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for ApexExpectationTool — all expectation types and edge cases.
 */
class ApexExpectationToolTest {

    private static ApexExpectationTool tool;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void setUp() {
        tool = ApexExpectationTool.builder().build();
    }

    // ===== OVERALL_SUCCESS =====

    @Test
    void overallSuccess_matchesExpected_passes() throws Exception {
        String actual = """
                {"success": true, "failureMessages": [], "failureCount": 0, "enrichedData": {}, "childResults": [], "childResultCount": 0}
                """;
        String expectations = """
                [{"type": "OVERALL_SUCCESS", "expected": true}]
                """;

        String result = tool.assertExpectedOutcomes(actual, expectations);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("overallPass").asBoolean()).isTrue();
        assertThat(json.get("totalAssertions").asInt()).isEqualTo(1);
        assertThat(json.get("passed").asInt()).isEqualTo(1);
    }

    @Test
    void overallSuccess_doesNotMatch_fails() throws Exception {
        String actual = """
                {"success": false, "failureMessages": ["Amount too high"], "failureCount": 1}
                """;
        String expectations = """
                [{"type": "OVERALL_SUCCESS", "expected": true}]
                """;

        String result = tool.assertExpectedOutcomes(actual, expectations);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("overallPass").asBoolean()).isFalse();
        assertThat(json.get("failed").asInt()).isEqualTo(1);
        assertThat(json.has("failureSummary")).isTrue();
        assertThat(json.has("errorClassification")).isTrue();

        // Check error classification has E_LOGIC_MISMATCH
        assertThat(json.get("errorClassification").get(0).get("errorCode").asText())
                .isEqualTo("E_LOGIC_MISMATCH");
    }

    // ===== FAILURE_COUNT =====

    @Test
    void failureCount_equals_passes() throws Exception {
        String actual = """
                {"success": false, "failureMessages": ["a", "b"], "failureCount": 2}
                """;
        String expectations = """
                [{"type": "FAILURE_COUNT", "operator": "EQUALS", "value": 2}]
                """;

        String result = tool.assertExpectedOutcomes(actual, expectations);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("overallPass").asBoolean()).isTrue();
    }

    @Test
    void failureCount_greaterThan_passes() throws Exception {
        String actual = """
                {"failureCount": 5}
                """;
        String expectations = """
                [{"type": "FAILURE_COUNT", "operator": "GREATER_THAN", "value": 2}]
                """;

        String result = tool.assertExpectedOutcomes(actual, expectations);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("overallPass").asBoolean()).isTrue();
    }

    @Test
    void failureCount_lessThan_fails() throws Exception {
        String actual = """
                {"failureCount": 5}
                """;
        String expectations = """
                [{"type": "FAILURE_COUNT", "operator": "LESS_THAN", "value": 3}]
                """;

        String result = tool.assertExpectedOutcomes(actual, expectations);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("overallPass").asBoolean()).isFalse();
    }

    // ===== ENRICHED_FIELD =====

    @Test
    void enrichedField_notNull_passes() throws Exception {
        String actual = """
                {"enrichedData": {"totalAmount": 500}}
                """;
        String expectations = """
                [{"type": "ENRICHED_FIELD", "field": "totalAmount", "operator": "NOT_NULL"}]
                """;

        String result = tool.assertExpectedOutcomes(actual, expectations);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("overallPass").asBoolean()).isTrue();
    }

    @Test
    void enrichedField_equals_passes() throws Exception {
        String actual = """
                {"enrichedData": {"status": "APPROVED"}}
                """;
        String expectations = """
                [{"type": "ENRICHED_FIELD", "field": "status", "operator": "EQUALS", "value": "APPROVED"}]
                """;

        String result = tool.assertExpectedOutcomes(actual, expectations);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("overallPass").asBoolean()).isTrue();
    }

    @Test
    void enrichedField_equals_failsOnMismatch() throws Exception {
        String actual = """
                {"enrichedData": {"status": "REJECTED"}}
                """;
        String expectations = """
                [{"type": "ENRICHED_FIELD", "field": "status", "operator": "EQUALS", "value": "APPROVED"}]
                """;

        String result = tool.assertExpectedOutcomes(actual, expectations);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("overallPass").asBoolean()).isFalse();
    }

    @Test
    void enrichedField_greaterThan_passes() throws Exception {
        String actual = """
                {"enrichedData": {"riskScore": 0.8}}
                """;
        String expectations = """
                [{"type": "ENRICHED_FIELD", "field": "riskScore", "operator": "GREATER_THAN", "value": 0.5}]
                """;

        String result = tool.assertExpectedOutcomes(actual, expectations);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("overallPass").asBoolean()).isTrue();
    }

    @Test
    void enrichedField_contains_passes() throws Exception {
        String actual = """
                {"enrichedData": {"description": "High risk transaction detected"}}
                """;
        String expectations = """
                [{"type": "ENRICHED_FIELD", "field": "description", "operator": "CONTAINS", "value": "risk"}]
                """;

        String result = tool.assertExpectedOutcomes(actual, expectations);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("overallPass").asBoolean()).isTrue();
    }

    @Test
    void enrichedField_missingField_fails() throws Exception {
        String actual = """
                {"enrichedData": {}}
                """;
        String expectations = """
                [{"type": "ENRICHED_FIELD", "field": "missing", "operator": "NOT_NULL"}]
                """;

        String result = tool.assertExpectedOutcomes(actual, expectations);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("overallPass").asBoolean()).isFalse();
    }

    @Test
    void enrichedField_nestedDotNotation_passes() throws Exception {
        String actual = """
                {"enrichedData": {"address": {"city": "London"}}}
                """;
        String expectations = """
                [{"type": "ENRICHED_FIELD", "field": "address.city", "operator": "EQUALS", "value": "London"}]
                """;

        String result = tool.assertExpectedOutcomes(actual, expectations);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("overallPass").asBoolean()).isTrue();
    }

    // ===== RULE_MATCH =====

    @Test
    void ruleMatch_ruleInFailures_matchesAsExpected() throws Exception {
        String actual = """
                {"success": false, "failureMessages": ["Rule high-amount violated: Amount too high"], "failureCount": 1, "enrichedData": {}}
                """;
        String expectations = """
                [{"type": "RULE_MATCH", "ruleId": "high-amount", "shouldMatch": true}]
                """;

        String result = tool.assertExpectedOutcomes(actual, expectations);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("overallPass").asBoolean()).isTrue();
    }

    @Test
    void ruleMatch_ruleNotInFailures_noMatchAsExpected() throws Exception {
        String actual = """
                {"success": true, "failureMessages": [], "failureCount": 0, "enrichedData": {}}
                """;
        String expectations = """
                [{"type": "RULE_MATCH", "ruleId": "high-amount", "shouldMatch": false}]
                """;

        String result = tool.assertExpectedOutcomes(actual, expectations);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("overallPass").asBoolean()).isTrue();
    }

    @Test
    void ruleMatch_expectedButNotFound_fails() throws Exception {
        String actual = """
                {"success": true, "failureMessages": [], "failureCount": 0, "enrichedData": {}}
                """;
        String expectations = """
                [{"type": "RULE_MATCH", "ruleId": "high-amount", "shouldMatch": true}]
                """;

        String result = tool.assertExpectedOutcomes(actual, expectations);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("overallPass").asBoolean()).isFalse();
    }

    @Test
    void ruleMatch_missingRuleId_fails() throws Exception {
        String actual = """
                {"failureMessages": []}
                """;
        String expectations = """
                [{"type": "RULE_MATCH", "shouldMatch": true}]
                """;

        String result = tool.assertExpectedOutcomes(actual, expectations);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("overallPass").asBoolean()).isFalse();
    }

    // ===== CHILD_RESULT_COUNT =====

    @Test
    void childResultCount_equalsZero_passes() throws Exception {
        String actual = """
                {"childResultCount": 0}
                """;
        String expectations = """
                [{"type": "CHILD_RESULT_COUNT", "operator": "EQUALS", "value": 0}]
                """;

        String result = tool.assertExpectedOutcomes(actual, expectations);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("overallPass").asBoolean()).isTrue();
    }

    // ===== Multiple expectations =====

    @Test
    void multipleExpectations_allPass() throws Exception {
        String actual = """
                {"success": false, "failureMessages": ["Rule r1 matched"], "failureCount": 1, "enrichedData": {"total": 500}, "childResultCount": 0}
                """;
        String expectations = """
                [
                  {"type": "OVERALL_SUCCESS", "expected": false},
                  {"type": "FAILURE_COUNT", "operator": "EQUALS", "value": 1},
                  {"type": "ENRICHED_FIELD", "field": "total", "operator": "EQUALS", "value": 500},
                  {"type": "CHILD_RESULT_COUNT", "operator": "EQUALS", "value": 0}
                ]
                """;

        String result = tool.assertExpectedOutcomes(actual, expectations);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("overallPass").asBoolean()).isTrue();
        assertThat(json.get("totalAssertions").asInt()).isEqualTo(4);
        assertThat(json.get("passed").asInt()).isEqualTo(4);
        assertThat(json.get("failed").asInt()).isEqualTo(0);
    }

    @Test
    void multipleExpectations_partialFailure() throws Exception {
        String actual = """
                {"success": true, "failureCount": 0, "enrichedData": {"total": 100}}
                """;
        String expectations = """
                [
                  {"type": "OVERALL_SUCCESS", "expected": true},
                  {"type": "ENRICHED_FIELD", "field": "total", "operator": "GREATER_THAN", "value": 500}
                ]
                """;

        String result = tool.assertExpectedOutcomes(actual, expectations);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("overallPass").asBoolean()).isFalse();
        assertThat(json.get("passed").asInt()).isEqualTo(1);
        assertThat(json.get("failed").asInt()).isEqualTo(1);
    }

    // ===== Edge cases =====

    @Test
    void wrappedExpectationsFormat_accepted() throws Exception {
        String actual = """
                {"success": true}
                """;
        String expectations = """
                {"expectations": [{"type": "OVERALL_SUCCESS", "expected": true}]}
                """;

        String result = tool.assertExpectedOutcomes(actual, expectations);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("overallPass").asBoolean()).isTrue();
    }

    @Test
    void unknownExpectationType_fails() throws Exception {
        String actual = """
                {"success": true}
                """;
        String expectations = """
                [{"type": "NONEXISTENT_TYPE"}]
                """;

        String result = tool.assertExpectedOutcomes(actual, expectations);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("overallPass").asBoolean()).isFalse();
        assertThat(json.get("assertions").get(0).get("reason").asText())
                .containsIgnoringCase("unknown");
    }

    @Test
    void invalidJson_handledGracefully() throws Exception {
        String result = tool.assertExpectedOutcomes("not-json", "[{\"type\":\"OVERALL_SUCCESS\"}]");
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("overallPass").asBoolean()).isFalse();
        assertThat(json.has("error")).isTrue();
    }
}
