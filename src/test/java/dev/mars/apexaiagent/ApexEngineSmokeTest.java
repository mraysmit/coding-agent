package dev.mars.apexaiagent;

import dev.mars.apex.engine.core.RulesEngine;
import dev.mars.apex.engine.model.RuleResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests verifying the APEX RulesEngine can be invoked from
 * this project (Spring Boot 4.x host). Tests the static evaluateYaml()
 * one-shot API which is the primary interface for the agent tools.
 * No Spring context needed — pure unit tests.
 */
class ApexEngineSmokeTest {

    // --- Basic rule evaluation ---

    @Test
    void evaluateYamlWithMatchingRule() {
        String yaml = """
                metadata:
                  id: "engine-smoke"
                  name: "Engine Smoke Test"
                  version: "1.0"
                  description: "Test rule evaluation"
                  type: "rule-config"
                  author: "test"
                
                rules:
                  - id: "check-amount"
                    condition: "#amount > 1000"
                    message: "Amount exceeds limit"
                    severity: "ERROR"
                """;

        Map<String, Object> data = Map.of("amount", 5000);
        RuleResult result = RulesEngine.evaluateYaml(yaml, data);

        assertThat(result).isNotNull();
        // Rule with ERROR severity matched -> isSuccess() is false
        // because the validation found a problem
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void evaluateYamlWithNonMatchingRule() {
        String yaml = """
                metadata:
                  id: "no-match-test"
                  name: "No Match Test"
                  version: "1.0"
                  description: "Rule should not match"
                  type: "rule-config"
                  author: "test"
                
                rules:
                  - id: "check-amount"
                    condition: "#amount > 1000"
                    message: "Amount exceeds limit"
                    severity: "ERROR"
                """;

        Map<String, Object> data = Map.of("amount", 500);
        RuleResult result = RulesEngine.evaluateYaml(yaml, data);

        assertThat(result).isNotNull();
    }

    @Test
    void evaluateYamlWithMultipleRules() {
        String yaml = """
                metadata:
                  id: "multi-rule"
                  name: "Multi Rule Test"
                  version: "1.0"
                  description: "Multiple rules"
                  type: "rule-config"
                  author: "test"
                
                rules:
                  - id: "r1"
                    condition: "#amount > 0"
                    message: "Amount is positive"
                    severity: "INFO"
                  - id: "r2"
                    condition: "#currency != null && #currency.length() == 3"
                    message: "Currency is valid 3-letter code"
                    severity: "WARNING"
                  - id: "r3"
                    condition: "#amount > 1000000"
                    message: "High value transaction"
                    severity: "ERROR"
                """;

        Map<String, Object> data = Map.of(
                "amount", 50000,
                "currency", "USD"
        );

        RuleResult result = RulesEngine.evaluateYaml(yaml, data);

        assertThat(result).isNotNull();
        // r1 (INFO) and r2 (WARNING) match; r3 (ERROR) does not
        // Verify the engine processed all rules without throwing
        assertThat(result.getEnrichedData()).isNotNull();
        assertThat(result.getFailureMessages()).isNotNull();
    }

    // --- Enrichment evaluation ---

    @Test
    void evaluateYamlWithCalculationEnrichment() {
        String yaml = """
                metadata:
                  id: "enrichment-test"
                  name: "Enrichment Test"
                  version: "1.0"
                  description: "Calculation enrichment"
                  type: "rule-config"
                  author: "test"
                
                enrichments:
                  - id: "calc-total"
                    type: "calculation-enrichment"
                    source-fields:
                      - "#quantity"
                      - "#price"
                    calculation:
                      expression: "#quantity * #price"
                      result-field: "totalValue"
                """;

        Map<String, Object> data = Map.of(
                "quantity", 10,
                "price", 25.50
        );

        RuleResult result = RulesEngine.evaluateYaml(yaml, data);

        assertThat(result).isNotNull();
        // Enrichment may store result in enrichedData or child results
        // Verify the engine processed without error
        assertThat(result.getEnrichedData()).isNotNull();
    }

    // --- Error handling ---

    @Test
    void evaluateYamlWithInvalidYamlReturnsErrorResult() {
        String badYaml = "not: valid: yaml: [[[";

        Map<String, Object> data = Map.of("x", 1);
        RuleResult result = RulesEngine.evaluateYaml(badYaml, data);

        // evaluateYaml never throws — wraps errors in RuleResult
        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void evaluateYamlWithMissingFieldHandledGracefully() {
        String yaml = """
                metadata:
                  id: "missing-field"
                  name: "Missing Field Test"
                  version: "1.0"
                  description: "Test missing input field"
                  type: "rule-config"
                  author: "test"
                
                rules:
                  - id: "r1"
                    condition: "#nonExistentField > 100"
                    message: "This references a missing field"
                    severity: "ERROR"
                """;

        Map<String, Object> data = Map.of("amount", 500);
        RuleResult result = RulesEngine.evaluateYaml(yaml, data);

        // Should handle gracefully, not throw
        assertThat(result).isNotNull();
    }

    // --- RuleResult field access ---

    @Test
    void ruleResultExposesMeaningfulFields() {
        String yaml = """
                metadata:
                  id: "field-access"
                  name: "Field Access Test"
                  version: "1.0"
                  description: "Verify RuleResult fields"
                  type: "rule-config"
                  author: "test"
                
                rules:
                  - id: "positive-amount"
                    condition: "#amount > 0"
                    message: "Amount is positive: {{#amount}}"
                    severity: "INFO"
                """;

        Map<String, Object> data = Map.of("amount", 42);
        RuleResult result = RulesEngine.evaluateYaml(yaml, data);

        assertThat(result).isNotNull();
        assertThat(result.getEnrichedData()).isNotNull();
        assertThat(result.getFailureMessages()).isNotNull();
    }
}
