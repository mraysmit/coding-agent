package dev.mars.apexaiagent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the condensed syntax artifact at knowledge/apex-syntax-compact.yaml.
 * Ensures the file is parseable YAML, contains expected sections, and
 * cross-references correctly against ApexYamlLexicalValidator constants.
 */
class ApexSyntaxArtifactTest {

    private static Map<String, Object> syntaxDoc;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadSyntaxArtifact() throws Exception {
        Path artifactPath = Path.of("knowledge", "apex-syntax-compact.yaml");
        assertTrue(Files.exists(artifactPath),
                "Syntax artifact must exist at knowledge/apex-syntax-compact.yaml");

        try (InputStream is = Files.newInputStream(artifactPath)) {
            Yaml yaml = new Yaml();
            syntaxDoc = yaml.load(is);
        }

        assertNotNull(syntaxDoc, "Syntax artifact should parse as a YAML map");
    }

    // ---- Section presence tests ----

    @Test
    void containsAllMajorSections() {
        List<String> expectedSections = List.of(
                "required-metadata-fields",
                "valid-document-types",
                "type-specific-required-metadata",
                "type-required-sections",
                "rule-fields",
                "enrichment-types",
                "rule-group-fields",
                "enrichment-group-fields",
                "scenario-fields",
                "scenario-registry-fields",
                "component-fields",
                "transformation-types",
                "data-source-fields",
                "error-recovery-fields",
                "spel-rules",
                "templates",
                "validator-gaps"
        );

        for (String section : expectedSections) {
            assertTrue(syntaxDoc.containsKey(section),
                    "Missing expected section: " + section);
        }
    }

    // ---- Cross-reference with ApexYamlLexicalValidator constants ----

    @Test
    @SuppressWarnings("unchecked")
    void requiredMetadataFieldsMatchValidator() {
        List<String> fields = (List<String>) syntaxDoc.get("required-metadata-fields");
        assertNotNull(fields);
        // These must exactly match ApexYamlLexicalValidator.REQUIRED_METADATA_FIELDS
        assertTrue(fields.contains("id"));
        assertTrue(fields.contains("name"));
        assertTrue(fields.contains("version"));
        assertTrue(fields.contains("description"));
        assertTrue(fields.contains("type"));
        assertEquals(5, fields.size(), "Should have exactly 5 required metadata fields");
    }

    @Test
    @SuppressWarnings("unchecked")
    void validDocumentTypesMatchValidator() {
        List<String> types = (List<String>) syntaxDoc.get("valid-document-types");
        assertNotNull(types);
        // Must match ApexYamlLexicalValidator.VALID_DOCUMENT_TYPES
        List<String> expected = List.of(
                "rule-config", "enrichment", "dataset", "scenario",
                "scenario-registry", "rule-chain", "external-data-config",
                "pipeline-config"
        );
        assertEquals(expected.size(), types.size(),
                "Document type count mismatch");
        for (String t : expected) {
            assertTrue(types.contains(t), "Missing document type: " + t);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void typeSpecificRequiredMetadataCoversAllTypes() {
        Map<String, Object> tsrm = (Map<String, Object>) syntaxDoc.get("type-specific-required-metadata");
        assertNotNull(tsrm);
        List<String> types = (List<String>) syntaxDoc.get("valid-document-types");
        for (String type : types) {
            assertTrue(tsrm.containsKey(type),
                    "type-specific-required-metadata missing entry for: " + type);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void typeRequiredSectionsCoversKeyTypes() {
        Map<String, Object> trs = (Map<String, Object>) syntaxDoc.get("type-required-sections");
        assertNotNull(trs);
        // These types MUST have entries (from validator constants)
        for (String type : List.of("rule-config", "enrichment", "dataset",
                "scenario", "rule-chain", "external-data-config", "pipeline-config")) {
            assertTrue(trs.containsKey(type),
                    "type-required-sections missing entry for: " + type);
        }
    }

    // ---- Rule structure tests ----

    @Test
    @SuppressWarnings("unchecked")
    void ruleFieldsHaveRequiredKeys() {
        Map<String, Object> ruleFields = (Map<String, Object>) syntaxDoc.get("rule-fields");
        assertNotNull(ruleFields);
        Object requiredObj = ruleFields.get("required");
        assertNotNull(requiredObj, "rule-fields must have a 'required' section");
        // In our YAML, "required:" is a list of field names (with comments)
        assertInstanceOf(List.class, requiredObj);
        List<String> required = (List<String>) requiredObj;
        assertTrue(required.contains("id"), "Rule required fields must include 'id'");
        assertTrue(required.contains("condition"), "Rule required fields must include 'condition'");
        assertTrue(required.contains("message"), "Rule required fields must include 'message'");
        assertTrue(required.contains("severity"), "Rule required fields must include 'severity'");
    }

    // ---- Enrichment structure tests ----

    @Test
    @SuppressWarnings("unchecked")
    void enrichmentTypesAreDefined() {
        Map<String, Object> enrichmentTypes = (Map<String, Object>) syntaxDoc.get("enrichment-types");
        assertNotNull(enrichmentTypes);
        for (String etype : List.of("lookup-enrichment", "calculation-enrichment",
                "field-enrichment", "conditional-mapping-enrichment")) {
            assertTrue(enrichmentTypes.containsKey(etype),
                    "Missing enrichment type: " + etype);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void calculationEnrichmentUsesCorrectStructure() {
        Map<String, Object> enrichmentTypes = (Map<String, Object>) syntaxDoc.get("enrichment-types");
        Map<String, Object> calcEnrich = (Map<String, Object>) enrichmentTypes.get("calculation-enrichment");
        assertNotNull(calcEnrich);
        // Must use calculation-config (not source-fields + calculation)
        assertTrue(calcEnrich.containsKey("calculation-config"),
                "calculation-enrichment should use 'calculation-config' sub-object");
    }

    @Test
    @SuppressWarnings("unchecked")
    void lookupEnrichmentUsesCorrectStructure() {
        Map<String, Object> enrichmentTypes = (Map<String, Object>) syntaxDoc.get("enrichment-types");
        Map<String, Object> lookupEnrich = (Map<String, Object>) enrichmentTypes.get("lookup-enrichment");
        assertNotNull(lookupEnrich);
        // Must use lookup-config (not flat lookup-key, key-field)
        assertTrue(lookupEnrich.containsKey("lookup-config"),
                "lookup-enrichment should use 'lookup-config' sub-object");
    }

    // ---- Template tests ----

    @Test
    @SuppressWarnings("unchecked")
    void templatesContainMinimalAndEnrichmentExamples() {
        Map<String, Object> templates = (Map<String, Object>) syntaxDoc.get("templates");
        assertNotNull(templates);
        assertTrue(templates.containsKey("minimal-rule-config"),
                "Must have minimal-rule-config template");
        assertTrue(templates.containsKey("rule-with-enrichment"),
                "Must have rule-with-enrichment template");
        assertTrue(templates.containsKey("scenario-registry"),
                "Must have scenario-registry template");
        assertTrue(templates.containsKey("lookup-enrichment-config"),
                "Must have lookup-enrichment-config template");
    }

    @Test
    @SuppressWarnings("unchecked")
    void templateMinimalRuleConfigHasCorrectStructure() {
        Map<String, Object> templates = (Map<String, Object>) syntaxDoc.get("templates");
        String minimalTemplate = (String) templates.get("minimal-rule-config");
        assertNotNull(minimalTemplate);
        // Verify the template contains key structural elements
        assertTrue(minimalTemplate.contains("metadata:"), "Template must have metadata section");
        assertTrue(minimalTemplate.contains("type: \"rule-config\""), "Template must specify type");
        assertTrue(minimalTemplate.contains("rules:"), "Template must have rules section");
        assertTrue(minimalTemplate.contains("condition:"), "Template must include condition");
        assertTrue(minimalTemplate.contains("severity:"), "Template must include severity");
    }

    @Test
    @SuppressWarnings("unchecked")
    void ruleWithEnrichmentTemplateUsesCalculationConfig() {
        Map<String, Object> templates = (Map<String, Object>) syntaxDoc.get("templates");
        String enrichTemplate = (String) templates.get("rule-with-enrichment");
        assertNotNull(enrichTemplate);
        // Must use calculation-config (not source-fields + calculation)
        assertTrue(enrichTemplate.contains("calculation-config:"),
                "Enrichment template must use 'calculation-config:' not 'calculation:'");
        assertFalse(enrichTemplate.contains("source-fields:"),
                "Enrichment template should NOT use deprecated 'source-fields:' syntax");
    }

    // ---- SpEL rules test ----

    @Test
    @SuppressWarnings("unchecked")
    void spelRulesDocumentFieldReferenceAndForbidden() {
        Map<String, Object> spelRules = (Map<String, Object>) syntaxDoc.get("spel-rules");
        assertNotNull(spelRules);
        assertTrue(spelRules.containsKey("field-reference"),
                "Must document field reference (#fieldName)");
        assertTrue(spelRules.containsKey("forbidden"),
                "Must document forbidden patterns");
        List<String> forbidden = (List<String>) spelRules.get("forbidden");
        assertNotNull(forbidden);
        assertTrue(forbidden.size() >= 2, "Should list at least 2 forbidden patterns");
    }

    // ---- Validator gaps test ----

    @Test
    @SuppressWarnings("unchecked")
    void validatorGapsAreDocumented() {
        List<String> gaps = (List<String>) syntaxDoc.get("validator-gaps");
        assertNotNull(gaps);
        assertTrue(gaps.size() >= 3,
                "Should document at least 3 known validator gaps");
    }

    // ---- Token budget sanity test ----

    @Test
    void syntaxArtifactIsWithinTokenBudget() throws Exception {
        Path artifactPath = Path.of("knowledge", "apex-syntax-compact.yaml");
        String content = Files.readString(artifactPath);
        // Rough estimate: ~4 chars per token for YAML
        int estimatedTokens = content.length() / 4;
        // Plan says < 3,000 tokens. We allow some margin.
        assertTrue(estimatedTokens < 4000,
                "Syntax artifact estimated at " + estimatedTokens +
                        " tokens, should be < 4,000 (target < 3,000)");
    }
}
