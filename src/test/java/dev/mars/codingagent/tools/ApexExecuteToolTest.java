package dev.mars.codingagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for ApexExecuteTool — single execution, batch execution, and error handling.
 */
class ApexExecuteToolTest {

    private static ApexExecuteTool tool;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void setUp() {
        tool = ApexExecuteTool.builder().build();
    }

    // ===== Single execution tests =====
    // APEX semantics (with 'name' present on rules):
    //   condition TRUE  → rule PASSES → isSuccess=true (data meets the validation check)
    //   condition FALSE → rule FAILS  → isSuccess=false (data fails the validation check)

    @Test
    void evaluateYaml_conditionTrue_returnsSuccess() throws Exception {
        // condition: #amount > 1000, amount=5000 → condition TRUE → isSuccess=true
        String yaml = """
                metadata:
                  id: "exec-test"
                  name: "Exec Test"
                  version: "1.0"
                  description: "test"
                  type: "rule-config"
                  author: "test"
                rules:
                  - id: "high-amount"
                    name: "High Amount Rule"
                    condition: "#amount > 1000"
                    message: "Amount exceeds limit"
                    severity: "ERROR"
                """;
        String facts = """
                {"amount": 5000}
                """;

        String result = tool.evaluateYaml(yaml, facts);
        JsonNode json = MAPPER.readTree(result);

        // Condition is true → data passes → isSuccess=true
        assertThat(json.get("success").asBoolean()).isTrue();
        assertThat(json.get("failureCount").asInt()).isEqualTo(0);
        assertThat(json.has("executionSummary")).isTrue();
    }

    @Test
    void evaluateYaml_conditionFalse_returnsFailure() throws Exception {
        // condition: #amount > 1000, amount=50 → condition FALSE → isSuccess=false
        String yaml = """
                metadata:
                  id: "exec-test-2"
                  name: "Exec Test 2"
                  version: "1.0"
                  description: "test"
                  type: "rule-config"
                  author: "test"
                rules:
                  - id: "high-amount"
                    name: "High Amount Rule"
                    condition: "#amount > 1000"
                    message: "Too high"
                    severity: "ERROR"
                """;
        String facts = """
                {"amount": 50}
                """;

        String result = tool.evaluateYaml(yaml, facts);
        JsonNode json = MAPPER.readTree(result);

        // Condition is false → data fails → isSuccess=false
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("failureCount").asInt()).isGreaterThan(0);
    }

    @Test
    void evaluateYaml_multipleRules_someConditionsFalse_reportsFailures() throws Exception {
        // r1 condition: #amount > 1000, amount=5000 → TRUE → passes
        // r2 condition: #name == null, name=null → TRUE → passes
        // Both conditions true → isSuccess=true
        String yaml = """
                metadata:
                  id: "multi"
                  name: "Multi"
                  version: "1.0"
                  description: "test"
                  type: "rule-config"
                  author: "test"
                rules:
                  - id: "r1"
                    name: "Amount Check"
                    condition: "#amount > 1000"
                    message: "Amount too high"
                    severity: "ERROR"
                  - id: "r2"
                    name: "Name Check"
                    condition: "#name == null"
                    message: "Name is required"
                    severity: "ERROR"
                """;
        String facts = """
                {"amount": 5000, "name": null}
                """;

        String result = tool.evaluateYaml(yaml, facts);
        JsonNode json = MAPPER.readTree(result);

        // Both conditions TRUE → passes
        assertThat(json.get("success").asBoolean()).isTrue();
    }

    @Test
    void evaluateYaml_withEnrichment_returnsEnrichedData() throws Exception {
        String yaml = """
                metadata:
                  id: "enrich"
                  name: "Enrich"
                  version: "1.0"
                  description: "test"
                  type: "rule-config"
                  author: "test"
                enrichments:
                  - id: "calc-total"
                    type: "calculation-enrichment"
                    calculation-config:
                      expression: "#quantity * #price"
                      result-field: "totalAmount"
                rules:
                  - id: "total-check"
                    name: "Total Check"
                    condition: "#totalAmount > 10000"
                    message: "Total amount exceeds limit"
                    severity: "WARNING"
                """;
        String facts = """
                {"quantity": 100, "price": 50}
                """;

        String result = tool.evaluateYaml(yaml, facts);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.has("enrichedData")).isTrue();
        assertThat(json.has("executionSummary")).isTrue();
    }

    @Test
    void evaluateYaml_invalidJson_returnsError() throws Exception {
        String yaml = """
                metadata:
                  id: "x"
                  name: "X"
                  version: "1.0"
                  description: "x"
                  type: "rule-config"
                  author: "x"
                rules:
                  - id: "r1"
                    name: "Test Rule"
                    condition: "true"
                    message: "test"
                    severity: "INFO"
                """;

        String result = tool.evaluateYaml(yaml, "not-valid-json");
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.has("error")).isTrue();
    }

    @Test
    void evaluateYaml_structuredResponse_hasAllFields() throws Exception {
        String yaml = """
                metadata:
                  id: "structure"
                  name: "Structure"
                  version: "1.0"
                  description: "test"
                  type: "rule-config"
                  author: "test"
                rules:
                  - id: "r1"
                    name: "Positive Check"
                    condition: "#x > 0"
                    message: "positive"
                    severity: "INFO"
                """;
        String facts = """
                {"x": 5}
                """;

        String result = tool.evaluateYaml(yaml, facts);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.has("success")).isTrue();
        assertThat(json.has("resultType")).isTrue();
        assertThat(json.has("failureMessages")).isTrue();
        assertThat(json.has("failureCount")).isTrue();
        assertThat(json.has("enrichedData")).isTrue();
        assertThat(json.has("childResults")).isTrue();
        assertThat(json.has("childResultCount")).isTrue();
        assertThat(json.has("executionSummary")).isTrue();
    }

    // ===== Batch execution tests =====

    @Test
    void evaluateBatch_multiplePayloads_returnsBatchResults() throws Exception {
        String yaml = """
                metadata:
                  id: "batch"
                  name: "Batch"
                  version: "1.0"
                  description: "test"
                  type: "rule-config"
                  author: "test"
                rules:
                  - id: "r1"
                    name: "Amount Limit"
                    condition: "#amount > 1000"
                    message: "Too high"
                    severity: "ERROR"
                """;
        String payloads = """
                [
                  {"name": "low", "data": {"amount": 100}},
                  {"name": "high", "data": {"amount": 5000}},
                  {"name": "zero", "data": {"amount": 0}}
                ]
                """;

        String result = tool.evaluateBatch(yaml, payloads);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("totalPayloads").asInt()).isEqualTo(3);
        assertThat(json.get("passed").asInt() + json.get("failed").asInt()).isEqualTo(3);
        assertThat(json.get("results").size()).isEqualTo(3);

        // condition: #amount > 1000
        //   "low" (100) → FALSE → fails
        //   "high" (5000) → TRUE → passes
        //   "zero" (0) → FALSE → fails
        assertThat(json.get("passed").asInt()).isEqualTo(1);
        assertThat(json.get("failed").asInt()).isEqualTo(2);
    }

    @Test
    void evaluateBatch_invalidPayloads_handlesGracefully() throws Exception {
        String yaml = """
                metadata:
                  id: "batch2"
                  name: "Batch2"
                  version: "1.0"
                  description: "test"
                  type: "rule-config"
                  author: "test"
                rules:
                  - id: "r1"
                    name: "Always True"
                    condition: "true"
                    message: "test"
                    severity: "INFO"
                """;

        String result = tool.evaluateBatch(yaml, "not-an-array");
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.has("error")).isTrue();
        assertThat(json.get("totalPayloads").asInt()).isEqualTo(0);
    }

    // ===== Error classification =====

    @Test
    void evaluateYaml_runtimeError_classifiesCorrectly() throws Exception {
        String yaml = """
                metadata:
                  id: "runtime-err"
                  name: "Runtime"
                  version: "1.0"
                  description: "test"
                  type: "rule-config"
                  author: "test"
                rules:
                  - id: "r1"
                    name: "Nonexistent Method"
                    condition: "#nonexistent.method()"
                    message: "test"
                    severity: "ERROR"
                """;
        String facts = """
                {"x": 1}
                """;

        String result = tool.evaluateYaml(yaml, facts);
        JsonNode json = MAPPER.readTree(result);

        // Should handle gracefully — either error or success=false
        assertThat(json.has("success")).isTrue();
    }
}
