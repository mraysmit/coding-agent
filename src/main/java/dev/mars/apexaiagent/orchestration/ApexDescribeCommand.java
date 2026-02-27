package dev.mars.apexaiagent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool exposed to the REPL ChatClient that delegates to the ApexDescriptionService.
 * When the user asks to explain, describe, or reverse-engineer APEX YAML rules,
 * the REPL agent calls this tool, which produces a plain-language business description.
 */
public class ApexDescribeCommand {

    private static final Logger log = LoggerFactory.getLogger(ApexDescribeCommand.class);

    private final ApexDescriptionService descriptionService;
    private final ObjectMapper objectMapper;

    public ApexDescribeCommand(ApexDescriptionService descriptionService) {
        this.descriptionService = descriptionService;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Tool(name = "DescribeApexRules", description = """
            Produces a plain-language business description of an APEX YAML configuration.
            Given the YAML content, this tool explains:
            - What the rule set does in business terms
            - What each rule checks (without SpEL jargon)
            - Which data fields are used
            
            Optionally accepts sample JSON data to trace which rules would PASS or FAIL
            against that data, with plain-English explanations of the outcomes.
            
            Use this when the user wants to:
            - Understand existing APEX rules in business terms
            - Reverse-engineer or document a YAML configuration
            - Explain what rules do to a non-technical stakeholder
            - Trace rule outcomes against sample data
            """)
    public String describeApexRules(
            @ToolParam(description = "The APEX YAML configuration content to describe") String yamlContent,
            @ToolParam(description = "Optional: sample JSON data to trace rule outcomes against. Pass empty string if not available.") String sampleJson) {
        log.info("[DescribeApexRules] Describing YAML ({} chars), sampleJson={} chars",
                yamlContent.length(), sampleJson != null ? sampleJson.length() : 0);
        try {
            DescriptionRequest request = DescriptionRequest.of(
                    yamlContent,
                    sampleJson != null && !sampleJson.isBlank() ? sampleJson : null
            );
            DescriptionResult result = descriptionService.describe(request);
            log.info("[DescribeApexRules] Complete. success={}, rules={}", result.success(), result.rules().size());
            return formatResult(result);
        } catch (Exception e) {
            return toJson(Map.of(
                    "success", false,
                    "error", "Description failed: " + e.getMessage()
            ));
        }
    }

    @Tool(name = "DescribeApexRulesWithFocus", description = """
            Like DescribeApexRules, but with a specific focus area.
            Use when the user wants to understand only part of a rule set,
            e.g. "explain only the discount rules" or "describe the VIP logic".
            """)
    public String describeApexRulesWithFocus(
            @ToolParam(description = "The APEX YAML configuration content to describe") String yamlContent,
            @ToolParam(description = "Sample JSON data to trace outcomes. Pass empty string if none.") String sampleJson,
            @ToolParam(description = "A specific aspect or subset to focus on, e.g. 'explain only the severity=ERROR rules'") String focusArea) {
        log.info("[DescribeApexRulesWithFocus] focus='{}', yamlContent={} chars", focusArea, yamlContent.length());
        try {
            DescriptionRequest request = DescriptionRequest.of(
                    yamlContent,
                    sampleJson != null && !sampleJson.isBlank() ? sampleJson : null,
                    focusArea
            );
            DescriptionResult result = descriptionService.describe(request);
            log.info("[DescribeApexRulesWithFocus] Complete. success={}", result.success());
            return formatResult(result);
        } catch (Exception e) {
            return toJson(Map.of(
                    "success", false,
                    "error", "Description failed: " + e.getMessage()
            ));
        }
    }

    // ---- Formatting ----

    private String formatResult(DescriptionResult result) {
        if (!result.success()) {
            return toJson(Map.of("success", false, "error", result.error()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("summary", result.summary());
        out.put("description", result.description());
        out.put("dataFieldsIdentified", result.dataFieldsIdentified());

        List<Map<String, Object>> rules = result.rules().stream().map(r -> {
            Map<String, Object> rule = new LinkedHashMap<>();
            rule.put("ruleId", r.ruleId());
            rule.put("ruleName", r.ruleName());
            rule.put("businessMeaning", r.businessMeaning());
            rule.put("condition", r.condition());
            rule.put("severity", r.severity());
            if (!r.exampleOutcomes().isEmpty()) {
                rule.put("exampleOutcomes", r.exampleOutcomes());
            }
            return rule;
        }).toList();

        out.put("rules", rules);
        return toJson(out);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{\"error\":\"Failed to serialize result: " + e.getMessage() + "\"}";
        }
    }
}
