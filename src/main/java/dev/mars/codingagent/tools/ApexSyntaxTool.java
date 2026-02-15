package dev.mars.codingagent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Typed tool providing APEX syntax reference data to the agent.
 * Loads the condensed syntax artifact and provides structured lookup methods.
 */
public class ApexSyntaxTool {

    private final ObjectMapper objectMapper;
    private final Map<String, Object> syntaxDoc;

    @SuppressWarnings("unchecked")
    private ApexSyntaxTool(ObjectMapper objectMapper, Path syntaxArtifactPath) throws IOException {
        this.objectMapper = objectMapper;
        var yaml = new org.yaml.snakeyaml.Yaml();
        try (var is = Files.newInputStream(syntaxArtifactPath)) {
            this.syntaxDoc = yaml.load(is);
        }
    }

    /**
     * Returns the canonical template for a given document type.
     */
    @Tool(name = "ApexGetDocTypeTemplate", description = """
            Returns the canonical YAML template for a given APEX document type.
            Supported types: rule-config, enrichment, scenario-registry, lookup-enrichment-config, rule-with-enrichment.
            Use this to get a correct starting skeleton before drafting YAML.
            """)
    public String getDocTypeTemplate(
            @ToolParam(description = "Document type or template name (e.g., 'rule-config', 'scenario-registry', 'lookup-enrichment-config')") String docType) {
        Map<String, Object> result = new LinkedHashMap<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> templates = (Map<String, Object>) syntaxDoc.get("templates");

        if (templates == null) {
            result.put("found", false);
            result.put("message", "No templates section in syntax artifact");
            return toJson(result);
        }

        // Map common docType names to template keys
        String templateKey = switch (docType.toLowerCase().trim()) {
            case "rule-config", "minimal-rule-config", "minimal" -> "minimal-rule-config";
            case "enrichment", "rule-with-enrichment" -> "rule-with-enrichment";
            case "lookup", "lookup-enrichment", "lookup-enrichment-config" -> "lookup-enrichment-config";
            case "scenario-registry" -> "scenario-registry";
            default -> docType;
        };

        if (templates.containsKey(templateKey)) {
            result.put("found", true);
            result.put("templateName", templateKey);
            result.put("template", templates.get(templateKey));
        } else {
            result.put("found", false);
            result.put("message", "No template for '" + docType + "'. Available: " + templates.keySet());
        }
        return toJson(result);
    }

    /**
     * Returns the required metadata and section fields for a document type.
     */
    @Tool(name = "ApexGetRequiredFields", description = """
            Returns the required metadata fields and body sections for a given APEX document type.
            Includes both the common required fields (id, name, version, description, type)
            and the type-specific required fields (e.g., author for rule-config).
            Also returns the required body sections (e.g., rules and/or enrichments for rule-config).
            """)
    @SuppressWarnings("unchecked")
    public String getRequiredFields(
            @ToolParam(description = "APEX document type (e.g., 'rule-config', 'scenario', 'dataset')") String docType) {
        Map<String, Object> result = new LinkedHashMap<>();

        // Common required metadata
        result.put("commonRequiredMetadata", syntaxDoc.get("required-metadata-fields"));

        // Type-specific required metadata
        Map<String, Object> typeSpecific = (Map<String, Object>) syntaxDoc.get("type-specific-required-metadata");
        if (typeSpecific != null && typeSpecific.containsKey(docType)) {
            result.put("typeSpecificMetadata", typeSpecific.get(docType));
        } else {
            result.put("typeSpecificMetadata", List.of());
            result.put("typeSpecificNote", "No type-specific metadata for '" + docType + "'");
        }

        // Required body sections
        Map<String, Object> sections = (Map<String, Object>) syntaxDoc.get("type-required-sections");
        if (sections != null && sections.containsKey(docType)) {
            result.put("requiredSections", sections.get(docType));
            result.put("sectionNote", "At least one of these sections must be present");
        } else {
            result.put("requiredSections", List.of());
        }

        // Rule fields if applicable
        if ("rule-config".equals(docType)) {
            result.put("ruleFields", syntaxDoc.get("rule-fields"));
        }

        // Valid document types for reference
        result.put("validDocumentTypes", syntaxDoc.get("valid-document-types"));

        return toJson(result);
    }

    /**
     * Returns keyword/structure rules for a specific YAML section.
     */
    @Tool(name = "ApexGetKeywordRules", description = """
            Returns the keyword rules and structure constraints for a specific APEX YAML section.
            Sections: rule-fields, enrichment-types, rule-group-fields, enrichment-group-fields,
            scenario-fields, transformation-types, data-source-fields, error-recovery-fields, spel-rules.
            Use this to understand the allowed fields and values for a given section.
            """)
    public String getKeywordRules(
            @ToolParam(description = "Section name (e.g., 'enrichment-types', 'rule-fields', 'spel-rules')") String section) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (syntaxDoc.containsKey(section)) {
            result.put("found", true);
            result.put("section", section);
            result.put("rules", syntaxDoc.get(section));
        } else {
            result.put("found", false);
            result.put("message", "Section '" + section + "' not found");
            result.put("availableSections", syntaxDoc.keySet());
        }

        // Always include validator gaps for awareness
        if (syntaxDoc.containsKey("validator-gaps")) {
            result.put("validatorGaps", syntaxDoc.get("validator-gaps"));
        }

        return toJson(result);
    }

    /**
     * Returns SpEL syntax rules and patterns.
     */
    @Tool(name = "ApexGetSpelRules", description = """
            Returns SpEL (Spring Expression Language) syntax rules for APEX YAML conditions.
            Includes field reference patterns, operators, forbidden patterns, and examples.
            Always consult this before writing SpEL expressions in APEX rules.
            """)
    public String getSpelRules() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("spelRules", syntaxDoc.get("spel-rules"));
        return toJson(result);
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
        private Path syntaxArtifactPath = Path.of("knowledge", "apex-syntax-compact.yaml");

        private Builder() {}

        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        public Builder syntaxArtifactPath(Path path) {
            this.syntaxArtifactPath = path;
            return this;
        }

        public ApexSyntaxTool build() {
            try {
                return new ApexSyntaxTool(objectMapper, syntaxArtifactPath);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load syntax artifact from " + syntaxArtifactPath, e);
            }
        }
    }
}
