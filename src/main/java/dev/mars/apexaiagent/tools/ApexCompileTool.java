package dev.mars.apexaiagent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.apex.compiler.ApexYamlCompiler;
import dev.mars.apex.compiler.lexical.ApexYamlLexicalValidator;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Typed tool for APEX YAML compilation and lexical validation.
 * Wraps ApexYamlCompiler and ApexYamlLexicalValidator with structured JSON responses.
 */
public class ApexCompileTool {

    private static final Logger log = LoggerFactory.getLogger(ApexCompileTool.class);

    private final ObjectMapper objectMapper;
    private final ApexYamlCompiler compiler;
    private final ApexYamlLexicalValidator validator;

    private ApexCompileTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.compiler = new ApexYamlCompiler();
        this.validator = new ApexYamlLexicalValidator();
    }

    /**
     * Validates YAML content using the ApexYamlLexicalValidator.
     * This is the most production-ready validation in APEX. It checks:
     * - YAML syntax (SnakeYAML parse)
     * - APEX structure (metadata, required fields, document type)
     * - type-specific section requirements
     * - SpEL expression well-formedness (double ##, unmatched parens)
     *
     * @param yamlContent the APEX YAML content to validate
     * @return structured JSON with errors, warnings, and info arrays
     */
    @Tool(name = "ApexValidateLexical", description = """
            Validates APEX YAML content using the ApexYamlLexicalValidator.
            Checks YAML syntax, APEX structure (metadata, required fields, document type),
            type-specific section requirements, and SpEL expression well-formedness.
            Returns structured JSON with errors[], warnings[], info[], and a valid boolean.
            Use this BEFORE attempting compilation or runtime execution.
            """)
    public String validateLexical(
            @ToolParam(description = "The APEX YAML content to validate") String yamlContent) {
        log.debug("[ApexValidateLexical] Validating YAML ({} chars). Preview: {}...",
                yamlContent.length(), yamlContent.substring(0, Math.min(200, yamlContent.length())));
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Path tempFile = Files.createTempFile("apex-validate-", ".yaml");
            try {
                Files.writeString(tempFile, yamlContent);
                var validationResult = validator.validateFile(tempFile);

                List<String> errors = validationResult.getErrors();
                List<String> warnings = validationResult.getWarnings();
                List<String> info = validationResult.getInfo();

                result.put("valid", validationResult.isValid());
                result.put("errors", errors);
                result.put("warnings", warnings);
                result.put("info", info);
                result.put("errorCount", errors.size());
                result.put("warningCount", warnings.size());
                log.debug("[ApexValidateLexical] valid={}, errors={}, warnings={}",
                        validationResult.isValid(), errors.size(), warnings.size());
                if (!errors.isEmpty()) {
                    log.debug("[ApexValidateLexical] First error: {}", errors.get(0));
                }

                // Classify errors for the agent
                if (!errors.isEmpty()) {
                    result.put("errorClassification", classifyErrors(errors));
                }
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (Exception e) {
            result.put("valid", false);
            result.put("errors", List.of("Validation failed with exception: " + e.getMessage()));
            result.put("warnings", List.of());
            result.put("info", List.of());
            result.put("errorCount", 1);
            result.put("warningCount", 0);
            result.put("errorClassification", List.of(Map.of(
                    "errorCode", "E_SYNTAX_YAML",
                    "message", e.getMessage()
            )));
        }
        return toJson(result);
    }

    /**
     * Compiles YAML content using the ApexYamlCompiler.
     * Returns compilation success/failure with messages.
     *
     * @param yamlContent the APEX YAML content to compile
     * @return structured JSON with success boolean and messages
     */
    @Tool(name = "ApexCompile", description = """
            Compiles APEX YAML content using the ApexYamlCompiler.
            Returns structured JSON with success boolean, message, and any compilation output.
            Use this after lexical validation passes to check deeper compilation issues.
            """)
    public String compile(
            @ToolParam(description = "The APEX YAML content to compile") String yamlContent) {
        log.debug("[ApexCompile] Compiling YAML ({} chars)", yamlContent.length());
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            var compilationResult = compiler.compile(yamlContent);

            result.put("success", compilationResult.success);
            result.put("message", compilationResult.message);
            log.debug("[ApexCompile] success={}, message='{}'",
                    compilationResult.success, compilationResult.message);

            if (compilationResult.generatedCode != null
                    && !compilationResult.generatedCode.isEmpty()) {
                result.put("generatedCode", compilationResult.generatedCode);
            }

            if (!compilationResult.success) {
                result.put("errorClassification", List.of(Map.of(
                        "errorCode", "E_SCHEMA_APEX",
                        "message", compilationResult.message
                )));
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "Compilation exception: " + e.getMessage());
            result.put("errorClassification", List.of(Map.of(
                    "errorCode", classifyException(e),
                    "message", e.getMessage()
            )));
        }
        return toJson(result);
    }

    /**
     * Runs both lexical validation and compilation in sequence.
     * Stops at lexical validation if errors are found.
     */
    @Tool(name = "ApexValidateAndCompile", description = """
            Runs BOTH lexical validation and compilation on APEX YAML content.
            First validates with the lexical validator; if errors are found, stops and returns them.
            If lexical validation passes, proceeds to compilation.
            Returns a combined result with both validation stages.
            This is the recommended single-call validation entry point.
            """)
    public String validateAndCompile(
            @ToolParam(description = "The APEX YAML content to validate and compile") String yamlContent) {
        log.debug("[ApexValidateAndCompile] Running full validation pipeline ({} chars)", yamlContent.length());
        Map<String, Object> combined = new LinkedHashMap<>();

        // Step 1: Lexical validation
        String lexicalJson = validateLexical(yamlContent);
        Map<String, Object> lexicalResult = fromJson(lexicalJson);
        combined.put("lexical", lexicalResult);

        boolean lexicalValid = Boolean.TRUE.equals(lexicalResult.get("valid"));
        if (!lexicalValid) {
            log.debug("[ApexValidateAndCompile] Stopped at lexical stage — errors found");
            combined.put("overallSuccess", false);
            combined.put("stoppedAt", "lexical");
            combined.put("suggestion", "Fix lexical errors before attempting compilation");
            return toJson(combined);
        }

        // Step 2: Compilation
        String compileJson = compile(yamlContent);
        Map<String, Object> compileResult = fromJson(compileJson);
        combined.put("compilation", compileResult);
        combined.put("overallSuccess", Boolean.TRUE.equals(compileResult.get("success")));
        combined.put("stoppedAt", "none");
        log.debug("[ApexValidateAndCompile] Pipeline complete. overallSuccess={}",
                combined.get("overallSuccess"));

        return toJson(combined);
    }

    // ---- Error classification helpers ----

    private List<Map<String, String>> classifyErrors(List<String> errors) {
        return errors.stream().map(error -> {
            Map<String, String> classified = new LinkedHashMap<>();
            if (error.contains("YAML") || error.contains("parse") || error.contains("syntax")) {
                classified.put("errorCode", "E_SYNTAX_YAML");
            } else if (error.contains("metadata") || error.contains("required field")
                    || error.contains("document type")) {
                classified.put("errorCode", "E_SCHEMA_APEX");
            } else if (error.contains("SpEL") || error.contains("##")
                    || error.contains("parenthes")) {
                classified.put("errorCode", "E_SPEL_PARSE");
            } else if (error.contains("section") || error.contains("keyword")) {
                classified.put("errorCode", "E_SCHEMA_APEX");
            } else {
                classified.put("errorCode", "E_SCHEMA_APEX");
            }
            classified.put("message", error);
            return classified;
        }).toList();
    }

    private String classifyException(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("yaml") || msg.contains("parse") || msg.contains("scanner")) {
            return "E_SYNTAX_YAML";
        } else if (msg.contains("spel") || msg.contains("expression")) {
            return "E_SPEL_PARSE";
        }
        return "E_SCHEMA_APEX";
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{\"error\": \"Failed to serialize result: " + e.getMessage() + "\"}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of("error", "Failed to deserialize: " + e.getMessage());
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

        public ApexCompileTool build() {
            return new ApexCompileTool(objectMapper);
        }
    }
}
