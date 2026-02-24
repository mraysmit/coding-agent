package dev.mars.apexaiagent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.mars.apexaiagent.orchestration.GenerationResult.GeneratedFile;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool exposed to the REPL ChatClient that delegates to the ApexGenerationService.
 * When the user asks to generate APEX rules, the REPL agent calls this tool,
 * which runs the full generation pipeline.
 */
public class ApexGenerateCommand {

    private static final Logger log = LoggerFactory.getLogger(ApexGenerateCommand.class);

    private final ApexGenerationService generationService;
    private final ObjectMapper objectMapper;

    public ApexGenerateCommand(ApexGenerationService generationService) {
        this.generationService = generationService;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Tool(name = "GenerateApexRules", description = """
            Generates APEX YAML business rule configurations from requirements and data structures.
            This runs the full APEX generation pipeline:
            1. Plans which artifacts to create
            2. Retrieves relevant templates and examples
            3. Authors the YAML configuration
            4. Validates (lexical, compile, execute, expectations)
            5. Retries on failure (up to 3 attempts)
            6. Packages output files
            
            Returns a JSON summary with:
            - success: whether generation completed successfully
            - files: list of generated files with paths
            - validationReport: compilation and execution results
            - summary: human-readable description
            
            Use this when the user asks to create, generate, or write APEX rules,
            business rules, validation rules, or YAML configurations.
            """)
    public String generateApexRules(
            @ToolParam(description = "Business requirements describing what rules to create") String requirements,
            @ToolParam(description = "JSON or text describing the data structure/schema the rules operate on") String dataStructure) {
        log.info("[GenerateApexRules] Starting generation. requirements={} chars, dataStructure={} chars",
                requirements.length(), dataStructure.length());
        try {
            GenerationRequest request = GenerationRequest.of(requirements, dataStructure);
            log.debug("[GenerateApexRules] Created request: {}", request.requestId());
            GenerationResult result = generationService.generate(request);
            log.info("[GenerateApexRules] Complete. success={}, files={}",
                    result.success(), result.files().size());
            return formatResult(result);
        } catch (Exception e) {
            return toJson(Map.of(
                    "success", false,
                    "error", "Generation failed: " + e.getMessage()
            ));
        }
    }

    @Tool(name = "GenerateApexRulesWithHints", description = """
            Like GenerateApexRules, but with additional hints for the generation agent.
            Hints can include preferred document types, feature requirements, or constraints.
            """)
    public String generateApexRulesWithHints(
            @ToolParam(description = "Business requirements") String requirements,
            @ToolParam(description = "Data structure (JSON or text)") String dataStructure,
            @ToolParam(description = "Comma-separated hints (e.g. 'use rule-groups, include enrichments')") String hints) {
        log.info("[GenerateApexRulesWithHints] Starting generation with hints='{}'", hints);
        try {
            List<String> hintList = hints != null
                    ? Arrays.stream(hints.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()
                    : List.of();
            GenerationRequest request = GenerationRequest.of(requirements, dataStructure, hintList);
            GenerationResult result = generationService.generate(request);
            return formatResult(result);
        } catch (Exception e) {
            return toJson(Map.of(
                    "success", false,
                    "error", "Generation failed: " + e.getMessage()
            ));
        }
    }

    // ---- Formatting ----

    private String formatResult(GenerationResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("requestId", result.requestId());
        output.put("success", result.success());
        output.put("attempts", result.attempts());

        List<Map<String, String>> fileList = new ArrayList<>();
        for (GeneratedFile f : result.files()) {
            Map<String, String> fileInfo = new LinkedHashMap<>();
            fileInfo.put("fileName", f.fileName());
            fileInfo.put("docType", f.docType());
            fileInfo.put("path", f.writtenTo() != null ? f.writtenTo().toString() : "n/a");
            fileList.add(fileInfo);
        }
        output.put("files", fileList);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("lexicalValid", result.validationReport().lexicalValid());
        report.put("compilationSuccess", result.validationReport().compilationSuccess());
        report.put("executionSuccess", result.validationReport().executionSuccess());
        report.put("expectationsPass", result.validationReport().expectationsPass());
        output.put("validationReport", report);

        output.put("summary", result.summary());
        if (result.outputDirectory() != null) {
            output.put("outputDirectory", result.outputDirectory().toString());
        }

        return toJson(output);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            // Safely escape the message to prevent JSON injection
            String safeMsg = e.getMessage() != null
                    ? e.getMessage().replace("\\", "\\\\").replace("\"", "\\\"")
                    : "unknown";
            return "{\"error\": \"Failed to serialize: " + safeMsg + "\"}"; 
        }
    }
}
