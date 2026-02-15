package dev.mars.codingagent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.apex.engine.core.RulesEngine;
import dev.mars.apex.engine.model.RuleResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.*;

/**
 * Typed tool for executing APEX YAML rules against JSON fact data.
 * Wraps RulesEngine.evaluateYaml() with structured JSON responses.
 */
public class ApexExecuteTool {

    private final ObjectMapper objectMapper;

    private ApexExecuteTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Evaluates APEX YAML rules against JSON fact data.
     *
     * @param yamlContent  the APEX YAML rule configuration
     * @param jsonFacts    JSON object representing the input facts
     * @return structured JSON with execution results
     */
    @Tool(name = "ApexExecute", description = """
            Executes APEX YAML rules against JSON input data using the RulesEngine.
            Returns structured JSON with:
            - success: whether the evaluation completed without rule violations
            - resultType: the type of result returned
            - matchedRules: list of rules that matched (triggered)
            - failureMessages: any failure/violation messages
            - enrichedData: data enriched during evaluation
            - childResults: nested rule results if applicable
            - executionSummary: counts and timing info
            
            NOTE: A rule with ERROR/CRITICAL severity that MATCHES means isSuccess()=false,
            because the rule found a validation problem. This is correct behavior.
            
            Use this after successful compilation to verify runtime behavior.
            """)
    public String evaluateYaml(
            @ToolParam(description = "The APEX YAML content defining rules/enrichments") String yamlContent,
            @ToolParam(description = "JSON object with input facts to evaluate against") String jsonFacts) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // Parse the JSON facts into a Map
            @SuppressWarnings("unchecked")
            Map<String, Object> facts = objectMapper.readValue(jsonFacts, Map.class);

            // Execute
            RuleResult ruleResult = RulesEngine.evaluateYaml(yamlContent, facts);

            // Build structured response
            result.put("success", ruleResult.isSuccess());
            result.put("resultType", ruleResult.getResultType() != null
                    ? ruleResult.getResultType().toString() : "UNKNOWN");

            // Failure messages
            List<String> failures = ruleResult.getFailureMessages();
            result.put("failureMessages", failures != null ? failures : List.of());
            result.put("failureCount", failures != null ? failures.size() : 0);

            // Enriched data
            Map<String, Object> enriched = ruleResult.getEnrichedData();
            result.put("enrichedData", enriched != null ? enriched : Map.of());

            // Child results (may be empty for inline evaluateYaml)
            List<RuleResult> children = ruleResult.getChildResults();
            if (children != null && !children.isEmpty()) {
                List<Map<String, Object>> childSummaries = new ArrayList<>();
                for (RuleResult child : children) {
                    Map<String, Object> childMap = new LinkedHashMap<>();
                    childMap.put("success", child.isSuccess());
                    childMap.put("resultType", child.getResultType() != null
                            ? child.getResultType().toString() : "UNKNOWN");
                    childMap.put("failureMessages",
                            child.getFailureMessages() != null ? child.getFailureMessages() : List.of());
                    childSummaries.add(childMap);
                }
                result.put("childResults", childSummaries);
                result.put("childResultCount", childSummaries.size());
            } else {
                result.put("childResults", List.of());
                result.put("childResultCount", 0);
            }

            // Execution summary
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("evaluated", true);
            summary.put("ruleViolationsFound", !ruleResult.isSuccess());
            result.put("executionSummary", summary);

        } catch (JsonProcessingException e) {
            result.put("success", false);
            result.put("error", "Invalid JSON input: " + e.getMessage());
            result.put("errorClassification", List.of(Map.of(
                    "errorCode", "E_SYNTAX_JSON",
                    "message", "Failed to parse JSON facts: " + e.getMessage()
            )));
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "Execution failed: " + e.getMessage());
            result.put("errorClassification", List.of(Map.of(
                    "errorCode", classifyRuntimeException(e),
                    "message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()
            )));
        }
        return toJson(result);
    }

    /**
     * Evaluates APEX YAML rules against multiple test payloads.
     * Useful for batch testing with different input scenarios.
     */
    @Tool(name = "ApexExecuteBatch", description = """
            Evaluates APEX YAML rules against multiple JSON test payloads.
            Returns an array of results, one per payload, with the same structure as ApexExecute.
            Use this to test multiple scenarios at once (happy path, edge cases, error cases).
            """)
    public String evaluateBatch(
            @ToolParam(description = "The APEX YAML content defining rules/enrichments") String yamlContent,
            @ToolParam(description = "JSON array of test payload objects, each with 'name' and 'data' fields") String jsonPayloadsArray) {
        Map<String, Object> batchResult = new LinkedHashMap<>();
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> payloads = objectMapper.readValue(jsonPayloadsArray, List.class);

            List<Map<String, Object>> results = new ArrayList<>();
            int passCount = 0;
            int failCount = 0;

            for (Map<String, Object> payload : payloads) {
                String name = payload.containsKey("name")
                        ? payload.get("name").toString() : "payload-" + (results.size() + 1);
                Object data = payload.containsKey("data") ? payload.get("data") : payload;

                String dataJson = objectMapper.writeValueAsString(data);
                String singleResult = evaluateYaml(yamlContent, dataJson);

                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(singleResult, Map.class);
                parsed.put("payloadName", name);
                results.add(parsed);

                if (Boolean.TRUE.equals(parsed.get("success"))) {
                    passCount++;
                } else {
                    failCount++;
                }
            }

            batchResult.put("totalPayloads", payloads.size());
            batchResult.put("passed", passCount);
            batchResult.put("failed", failCount);
            batchResult.put("results", results);

        } catch (JsonProcessingException e) {
            batchResult.put("error", "Invalid JSON payloads array: " + e.getMessage());
            batchResult.put("totalPayloads", 0);
        } catch (Exception e) {
            batchResult.put("error", "Batch execution failed: " + e.getMessage());
            batchResult.put("totalPayloads", 0);
        }
        return toJson(batchResult);
    }

    // ---- Helpers ----

    private String classifyRuntimeException(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("spel") || msg.contains("expression") || msg.contains("evaluation")) {
            return "E_SPEL_RUNTIME";
        } else if (msg.contains("not found") || msg.contains("resolve") || msg.contains("reference")) {
            return "E_REF_RESOLUTION";
        } else if (msg.contains("yaml") || msg.contains("parse")) {
            return "E_SYNTAX_YAML";
        }
        return "E_ENGINE_RUNTIME";
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

        public ApexExecuteTool build() {
            return new ApexExecuteTool(objectMapper);
        }
    }
}
