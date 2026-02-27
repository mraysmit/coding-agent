package dev.mars.apexaiagent.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.apexaiagent.tools.ApexSyntaxTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;

/**
 * Reverse-direction pipeline: reads an APEX YAML configuration (plus optional
 * sample JSON facts) and produces a plain-language business description.
 *
 * <p>The description includes:
 * <ul>
 *   <li>A narrative summary of what the rule-set does</li>
 *   <li>A per-rule breakdown in business terms (no SpEL jargon)</li>
 *   <li>The data fields the rules operate on</li>
 *   <li>Example outcomes when sample data is supplied</li>
 * </ul>
 *
 * <p>The LLM is equipped with {@link ApexSyntaxTool} so it can resolve any
 * APEX-specific keywords it encounters while reading the YAML.
 */
public class ApexDescriptionService {

    private static final Logger log = LoggerFactory.getLogger(ApexDescriptionService.class);

    private final ChatClient descriptionChatClient;
    private final ObjectMapper objectMapper;

    private ApexDescriptionService(ChatClient descriptionChatClient) {
        this.descriptionChatClient = descriptionChatClient;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Derive a business description from an APEX YAML configuration and optional data.
     *
     * @param request the description request
     * @return structured description result
     */
    public DescriptionResult describe(DescriptionRequest request) {
        log.info("Starting APEX description for request: {}", request.requestId());

        String userPrompt = buildPrompt(request);
        log.debug("Built description prompt ({} chars)", userPrompt.length());

        try {
            String response = descriptionChatClient.prompt(userPrompt)
                    .toolContext(Map.of("workingDir", System.getProperty("user.dir")))
                    .call()
                    .content();

            if (response == null || response.isBlank()) {
                log.warn("Empty response from LLM for description request {}", request.requestId());
                return DescriptionResult.failed(request.requestId(), "No response from model");
            }

            log.debug("Received description response ({} chars)", response.length());
            return parseResponse(request.requestId(), response);

        } catch (Exception e) {
            log.error("Description failed for request {}: {}", request.requestId(), e.getMessage(), e);
            return DescriptionResult.failed(request.requestId(), "Description failed: " + e.getMessage());
        }
    }

    // ---- Prompt Construction ----

    private String buildPrompt(DescriptionRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Task\n\n");
        sb.append("""
                You are an expert APEX rules analyst. Your task is to produce a clear,
                plain-language **business description** of the APEX YAML configuration
                provided below. Write for a business stakeholder who does not know
                Spring Expression Language (SpEL) or YAML.
                
                """);

        if (request.focusArea() != null) {
            sb.append("Focus specifically on: ").append(request.focusArea()).append("\n\n");
        }

        sb.append("## APEX YAML Configuration\n\n```yaml\n");
        sb.append(request.yamlContent());
        sb.append("\n```\n\n");

        if (request.sampleJson() != null) {
            sb.append("## Sample Data\n\n```json\n");
            sb.append(request.sampleJson());
            sb.append("\n```\n\n");
            sb.append("""
                    Using the sample data above, trace through each rule and describe
                    which rules would PASS and which would FAIL, and why — in plain English.
                    
                    """);
        }

        sb.append("""
                ## Required Output Format
                
                Respond with a JSON object (no markdown fences) with this exact structure:
                {
                  "summary": "<2-3 sentence overview of what this rule set does>",
                  "description": "<detailed narrative explanation in business terms, multiple paragraphs>",
                  "dataFieldsIdentified": ["<field1>", "<field2>", ...],
                  "rules": [
                    {
                      "ruleId": "<id from yaml>",
                      "ruleName": "<name from yaml>",
                      "businessMeaning": "<plain English explanation of what this rule checks>",
                      "condition": "<the original SpEL condition>",
                      "severity": "<severity level>",
                      "exampleOutcomes": ["<outcome with sample data if provided>"]
                    }
                  ]
                }
                
                Output ONLY the JSON object. No preamble, no markdown fences.
                """);

        return sb.toString();
    }

    // ---- Response Parsing ----

    private DescriptionResult parseResponse(String requestId, String response) {
        // Strip any accidental markdown fences the model may have added
        String json = response.trim();
        if (json.startsWith("```")) {
            int start = json.indexOf('\n');
            int end = json.lastIndexOf("```");
            if (start >= 0 && end > start) {
                json = json.substring(start + 1, end).trim();
            }
        }

        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});

            String summary = (String) parsed.getOrDefault("summary", "");
            String description = (String) parsed.getOrDefault("description", "");

            @SuppressWarnings("unchecked")
            List<String> dataFields = (List<String>) parsed.getOrDefault("dataFieldsIdentified", List.of());

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawRules = (List<Map<String, Object>>) parsed.getOrDefault("rules", List.of());

            List<DescriptionResult.RuleDescription> rules = rawRules.stream().map(r -> {
                @SuppressWarnings("unchecked")
                List<String> outcomes = (List<String>) r.getOrDefault("exampleOutcomes", List.of());
                return new DescriptionResult.RuleDescription(
                        (String) r.getOrDefault("ruleId", ""),
                        (String) r.getOrDefault("ruleName", ""),
                        (String) r.getOrDefault("businessMeaning", ""),
                        (String) r.getOrDefault("condition", ""),
                        (String) r.getOrDefault("severity", ""),
                        outcomes
                );
            }).toList();

            log.info("Description parsed: {} rules, {} data fields", rules.size(), dataFields.size());
            return DescriptionResult.success(requestId, description, summary, rules, dataFields);

        } catch (Exception e) {
            log.warn("Failed to parse structured JSON from LLM response ({}): {}",
                    e.getMessage(), json.substring(0, Math.min(300, json.length())));
            // Fall back to returning the raw text as the description
            return DescriptionResult.success(requestId, response, extractFirstSentence(response),
                    List.of(), List.of());
        }
    }

    private String extractFirstSentence(String text) {
        if (text == null || text.isBlank()) return "";
        int dot = text.indexOf('.');
        return dot > 0 ? text.substring(0, dot + 1).trim() : text.substring(0, Math.min(120, text.length()));
    }

    // ---- Builder ----

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private ChatModel chatModel;

        private Builder() {}

        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        public ApexDescriptionService build() {
            if (chatModel == null) {
                throw new IllegalStateException("chatModel is required");
            }

            // APEX syntax tool lets the LLM resolve any keywords it encounters
            ChatClient client = ChatClient.builder(chatModel)
                    .defaultSystem("""
                            You are an APEX rules analyst who produces plain-language business
                            descriptions from APEX YAML configurations. You may use the
                            ApexSyntaxTool to look up the meaning of any APEX keywords
                            you encounter. Always explain rules in terms a business stakeholder
                            can understand — no SpEL, no YAML jargon.
                            """)
                    .defaultTools(ApexSyntaxTool.builder().build())
                    .defaultAdvisors(ToolCallAdvisor.builder().build())
                    .build();

            return new ApexDescriptionService(client);
        }
    }
}
