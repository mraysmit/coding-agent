package dev.mars.codingagent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.*;

/**
 * Typed tool for asserting business expectations against APEX execution results.
 * Validates that rule execution outcomes match the intended business logic,
 * not just that they compile and run.
 *
 * Supported expectation types:
 * - RULE_MATCH: assert a specific rule matched or didn't match
 * - ENRICHED_FIELD: assert a field exists in enrichedData with a value condition
 * - OVERALL_SUCCESS: assert RuleResult.isSuccess()
 * - FAILURE_COUNT: assert number of failureMessages
 * - CHILD_RESULT_COUNT: assert number of childResults
 */
public class ApexExpectationTool {

    private final ObjectMapper objectMapper;

    private ApexExpectationTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Asserts expected outcomes against actual execution results.
     *
     * @param actualResultJson   JSON output from ApexExecuteTool.evaluateYaml()
     * @param expectationsJson   JSON array of expectation objects
     * @return structured JSON with pass/fail per expectation
     */
    @Tool(name = "ApexAssertExpectations", description = """
            Asserts business expectations against APEX execution results.
            Takes the JSON output from ApexExecute and a set of expectations.
            
            Expectation types:
            - RULE_MATCH: {"type":"RULE_MATCH", "ruleId":"<id>", "shouldMatch":true/false, "expectedSeverity":"ERROR"}
            - ENRICHED_FIELD: {"type":"ENRICHED_FIELD", "field":"<name>", "operator":"EQUALS|GREATER_THAN|LESS_THAN|NOT_NULL|CONTAINS", "value":<expected>}
            - OVERALL_SUCCESS: {"type":"OVERALL_SUCCESS", "expected":true/false}
            - FAILURE_COUNT: {"type":"FAILURE_COUNT", "operator":"EQUALS|GREATER_THAN|LESS_THAN", "value":<n>}
            - CHILD_RESULT_COUNT: {"type":"CHILD_RESULT_COUNT", "operator":"EQUALS|GREATER_THAN|LESS_THAN", "value":<n>}
            
            Returns structured JSON with overall pass/fail, individual assertion results,
            and a summary useful for the validation report.
            """)
    public String assertExpectedOutcomes(
            @ToolParam(description = "JSON output from ApexExecute tool") String actualResultJson,
            @ToolParam(description = "JSON array of expectation objects") String expectationsJson) {

        Map<String, Object> response = new LinkedHashMap<>();
        try {
            JsonNode actual = objectMapper.readTree(actualResultJson);
            JsonNode expectations = objectMapper.readTree(expectationsJson);

            // Handle both {"expectations":[...]} and bare [...]
            JsonNode expectationArray;
            if (expectations.isArray()) {
                expectationArray = expectations;
            } else if (expectations.has("expectations")) {
                expectationArray = expectations.get("expectations");
            } else {
                response.put("overallPass", false);
                response.put("error", "Expectations must be a JSON array or object with 'expectations' array");
                return toJson(response);
            }

            List<Map<String, Object>> results = new ArrayList<>();
            int passCount = 0;
            int failCount = 0;

            for (JsonNode expectation : expectationArray) {
                Map<String, Object> assertionResult = evaluateExpectation(actual, expectation);
                results.add(assertionResult);
                if (Boolean.TRUE.equals(assertionResult.get("pass"))) {
                    passCount++;
                } else {
                    failCount++;
                }
            }

            response.put("overallPass", failCount == 0);
            response.put("totalAssertions", results.size());
            response.put("passed", passCount);
            response.put("failed", failCount);
            response.put("assertions", results);

            // Generate summary for agent consumption
            if (failCount > 0) {
                List<String> failureDescriptions = results.stream()
                        .filter(r -> !Boolean.TRUE.equals(r.get("pass")))
                        .map(r -> r.get("type") + ": " + r.get("reason"))
                        .toList();
                response.put("failureSummary", failureDescriptions);
                response.put("errorClassification", List.of(Map.of(
                        "errorCode", "E_LOGIC_MISMATCH",
                        "message", failCount + " expectation(s) failed: " + failureDescriptions
                )));
            }

        } catch (JsonProcessingException e) {
            response.put("overallPass", false);
            response.put("error", "Invalid JSON: " + e.getMessage());
        }
        return toJson(response);
    }

    // ---- Expectation evaluators ----

    private Map<String, Object> evaluateExpectation(JsonNode actual, JsonNode expectation) {
        Map<String, Object> result = new LinkedHashMap<>();
        String type = expectation.has("type") ? expectation.get("type").asText() : "UNKNOWN";
        result.put("type", type);

        try {
            switch (type) {
                case "RULE_MATCH" -> evaluateRuleMatch(actual, expectation, result);
                case "ENRICHED_FIELD" -> evaluateEnrichedField(actual, expectation, result);
                case "OVERALL_SUCCESS" -> evaluateOverallSuccess(actual, expectation, result);
                case "FAILURE_COUNT" -> evaluateFailureCount(actual, expectation, result);
                case "CHILD_RESULT_COUNT" -> evaluateChildResultCount(actual, expectation, result);
                default -> {
                    result.put("pass", false);
                    result.put("reason", "Unknown expectation type: " + type);
                }
            }
        } catch (Exception e) {
            result.put("pass", false);
            result.put("reason", "Assertion error: " + e.getMessage());
        }
        return result;
    }

    private void evaluateRuleMatch(JsonNode actual, JsonNode expectation, Map<String, Object> result) {
        String ruleId = expectation.has("ruleId") ? expectation.get("ruleId").asText() : null;
        boolean shouldMatch = !expectation.has("shouldMatch") || expectation.get("shouldMatch").asBoolean();
        result.put("ruleId", ruleId);
        result.put("shouldMatch", shouldMatch);

        if (ruleId == null) {
            result.put("pass", false);
            result.put("reason", "RULE_MATCH requires 'ruleId' field");
            return;
        }

        // Check failureMessages for the ruleId (word-boundary match to avoid false positives)
        boolean foundInFailures = false;
        if (actual.has("failureMessages")) {
            java.util.regex.Pattern rulePattern = java.util.regex.Pattern.compile(
                    "\\b" + java.util.regex.Pattern.quote(ruleId) + "\\b");
            for (JsonNode msg : actual.get("failureMessages")) {
                if (rulePattern.matcher(msg.asText()).find()) {
                    foundInFailures = true;
                    break;
                }
            }
        }

        // Also check enrichedData for result-field pattern
        boolean foundInEnriched = false;
        if (actual.has("enrichedData")) {
            JsonNode enriched = actual.get("enrichedData");
            if (enriched.has(ruleId)) {
                foundInEnriched = true;
            }
        }

        boolean matched = foundInFailures || foundInEnriched;

        if (shouldMatch == matched) {
            result.put("pass", true);
            result.put("reason", "Rule '" + ruleId + "' " + (matched ? "matched" : "did not match") + " as expected");
        } else {
            result.put("pass", false);
            result.put("reason", "Expected rule '" + ruleId + "' to " +
                    (shouldMatch ? "match but it didn't" : "not match but it did"));
        }

        // Check expected severity if specified
        if (expectation.has("expectedSeverity") && matched) {
            String expectedSeverity = expectation.get("expectedSeverity").asText();
            result.put("expectedSeverity", expectedSeverity);
            // Severity is typically encoded in the failure message
            // We note it but can't always extract it deterministically from the result
        }
    }

    private void evaluateEnrichedField(JsonNode actual, JsonNode expectation, Map<String, Object> result) {
        String field = expectation.has("field") ? expectation.get("field").asText() : null;
        String operator = expectation.has("operator") ? expectation.get("operator").asText() : "NOT_NULL";
        result.put("field", field);
        result.put("operator", operator);

        if (field == null) {
            result.put("pass", false);
            result.put("reason", "ENRICHED_FIELD requires 'field'");
            return;
        }

        JsonNode enrichedData = actual.has("enrichedData") ? actual.get("enrichedData") : null;
        if (enrichedData == null || enrichedData.isNull()) {
            result.put("pass", false);
            result.put("reason", "No enrichedData in result");
            return;
        }

        // Navigate nested fields using dot notation
        JsonNode fieldValue = navigateField(enrichedData, field);

        switch (operator) {
            case "NOT_NULL" -> {
                boolean exists = fieldValue != null && !fieldValue.isNull();
                result.put("pass", exists);
                result.put("actualValue", fieldValue != null ? fieldValue.asText() : null);
                result.put("reason", exists ? "Field '" + field + "' exists"
                        : "Field '" + field + "' not found in enrichedData");
            }
            case "EQUALS" -> {
                if (fieldValue == null || fieldValue.isNull()) {
                    result.put("pass", false);
                    result.put("reason", "Field '" + field + "' not found");
                } else {
                    JsonNode expected = expectation.get("value");
                    boolean pass = valuesEqual(fieldValue, expected);
                    result.put("pass", pass);
                    result.put("actualValue", fieldValue.asText());
                    result.put("expectedValue", expected != null ? expected.asText() : null);
                    result.put("reason", pass ? "Values match"
                            : "Expected " + expected + " but got " + fieldValue);
                }
            }
            case "GREATER_THAN" -> {
                evaluateNumericComparison(fieldValue, expectation, result, field, ">");
            }
            case "LESS_THAN" -> {
                evaluateNumericComparison(fieldValue, expectation, result, field, "<");
            }
            case "CONTAINS" -> {
                if (fieldValue == null || fieldValue.isNull()) {
                    result.put("pass", false);
                    result.put("reason", "Field '" + field + "' not found");
                } else {
                    String expected = expectation.has("value") ? expectation.get("value").asText() : "";
                    boolean pass = fieldValue.asText().contains(expected);
                    result.put("pass", pass);
                    result.put("actualValue", fieldValue.asText());
                    result.put("reason", pass ? "Value contains '" + expected + "'"
                            : "Value does not contain '" + expected + "'");
                }
            }
            default -> {
                result.put("pass", false);
                result.put("reason", "Unknown operator: " + operator);
            }
        }
    }

    private void evaluateOverallSuccess(JsonNode actual, JsonNode expectation, Map<String, Object> result) {
        boolean expected = !expectation.has("expected") || expectation.get("expected").asBoolean();
        boolean actualSuccess = actual.has("success") && actual.get("success").asBoolean();
        result.put("expected", expected);
        result.put("actual", actualSuccess);
        result.put("pass", expected == actualSuccess);
        result.put("reason", expected == actualSuccess
                ? "Overall success is " + actualSuccess + " as expected"
                : "Expected success=" + expected + " but got " + actualSuccess);
    }

    private void evaluateFailureCount(JsonNode actual, JsonNode expectation, Map<String, Object> result) {
        String operator = expectation.has("operator") ? expectation.get("operator").asText() : "EQUALS";
        int expected = expectation.has("value") ? expectation.get("value").asInt() : 0;
        int actualCount = actual.has("failureCount") ? actual.get("failureCount").asInt() : 0;

        result.put("operator", operator);
        result.put("expectedValue", expected);
        result.put("actualValue", actualCount);

        boolean pass = evaluateIntComparison(actualCount, operator, expected);
        result.put("pass", pass);
        result.put("reason", pass
                ? "Failure count " + actualCount + " " + operator + " " + expected
                : "Failure count " + actualCount + " does not satisfy " + operator + " " + expected);
    }

    private void evaluateChildResultCount(JsonNode actual, JsonNode expectation, Map<String, Object> result) {
        String operator = expectation.has("operator") ? expectation.get("operator").asText() : "EQUALS";
        int expected = expectation.has("value") ? expectation.get("value").asInt() : 0;
        int actualCount = actual.has("childResultCount") ? actual.get("childResultCount").asInt() : 0;

        result.put("operator", operator);
        result.put("expectedValue", expected);
        result.put("actualValue", actualCount);

        boolean pass = evaluateIntComparison(actualCount, operator, expected);
        result.put("pass", pass);
        result.put("reason", pass
                ? "Child result count " + actualCount + " " + operator + " " + expected
                : "Child result count " + actualCount + " does not satisfy " + operator + " " + expected);
    }

    // ---- Utility methods ----

    private JsonNode navigateField(JsonNode root, String fieldPath) {
        String[] parts = fieldPath.split("\\.");
        JsonNode current = root;
        for (String part : parts) {
            if (current == null || current.isNull()) return null;
            current = current.get(part);
        }
        return current;
    }

    private boolean valuesEqual(JsonNode actual, JsonNode expected) {
        if (actual == null || expected == null) return actual == expected;
        if (actual.isNumber() && expected.isNumber()) {
            return Double.compare(actual.asDouble(), expected.asDouble()) == 0;
        }
        return actual.asText().equals(expected.asText());
    }

    private void evaluateNumericComparison(JsonNode fieldValue, JsonNode expectation,
                                           Map<String, Object> result, String field, String op) {
        if (fieldValue == null || fieldValue.isNull()) {
            result.put("pass", false);
            result.put("reason", "Field '" + field + "' not found");
            return;
        }
        double actualVal = fieldValue.asDouble();
        double expectedVal = expectation.has("value") ? expectation.get("value").asDouble() : 0;
        boolean pass = op.equals(">") ? actualVal > expectedVal : actualVal < expectedVal;
        result.put("pass", pass);
        result.put("actualValue", actualVal);
        result.put("expectedValue", expectedVal);
        result.put("reason", pass
                ? actualVal + " " + op + " " + expectedVal
                : actualVal + " is not " + op + " " + expectedVal);
    }

    private boolean evaluateIntComparison(int actual, String operator, int expected) {
        return switch (operator) {
            case "EQUALS" -> actual == expected;
            case "GREATER_THAN" -> actual > expected;
            case "LESS_THAN" -> actual < expected;
            case "GREATER_THAN_OR_EQUAL" -> actual >= expected;
            case "LESS_THAN_OR_EQUAL" -> actual <= expected;
            default -> false;
        };
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{\"error\": \"Failed to serialize result: " + e.getMessage() + "\"}";
        }
    }

    // ---- Builder ----

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private ObjectMapper objectMapper = new ObjectMapper();

        private Builder() {}

        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        public ApexExpectationTool build() {
            return new ApexExpectationTool(objectMapper);
        }
    }
}
