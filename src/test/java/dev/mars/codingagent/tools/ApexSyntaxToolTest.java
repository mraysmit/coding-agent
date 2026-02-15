package dev.mars.codingagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for ApexSyntaxTool — template lookup, required fields, keyword rules, SpEL rules.
 */
class ApexSyntaxToolTest {

    private static ApexSyntaxTool tool;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void setUp() {
        tool = ApexSyntaxTool.builder()
                .syntaxArtifactPath(Path.of("knowledge", "apex-syntax-compact.yaml"))
                .build();
    }

    // ===== Template lookup =====

    @Test
    void getDocTypeTemplate_ruleConfig_returnsTemplate() throws Exception {
        String result = tool.getDocTypeTemplate("rule-config");
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("found").asBoolean()).isTrue();
        String template = json.get("template").asText();
        assertThat(template).contains("metadata:");
        assertThat(template).contains("type: \"rule-config\"");
        assertThat(template).contains("rules:");
    }

    @Test
    void getDocTypeTemplate_scenarioRegistry_returnsTemplate() throws Exception {
        String result = tool.getDocTypeTemplate("scenario-registry");
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("found").asBoolean()).isTrue();
        String template = json.get("template").asText();
        assertThat(template).contains("scenario-registry");
        assertThat(template).contains("scenarios:");
    }

    @Test
    void getDocTypeTemplate_lookupEnrichment_returnsTemplate() throws Exception {
        String result = tool.getDocTypeTemplate("lookup-enrichment");
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("found").asBoolean()).isTrue();
        String template = json.get("template").asText();
        assertThat(template).contains("lookup-enrichment");
        assertThat(template).contains("lookup-config:");
    }

    @Test
    void getDocTypeTemplate_nonexistent_returnsNotFound() throws Exception {
        String result = tool.getDocTypeTemplate("nonexistent");
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("found").asBoolean()).isFalse();
        assertThat(json.has("message")).isTrue();
    }

    // ===== Required fields =====

    @Test
    void getRequiredFields_ruleConfig_returnsAllFields() throws Exception {
        String result = tool.getRequiredFields("rule-config");
        JsonNode json = MAPPER.readTree(result);

        // Common metadata
        assertThat(json.get("commonRequiredMetadata").size()).isEqualTo(5);

        // Type-specific
        assertThat(json.get("typeSpecificMetadata").toString()).contains("author");

        // Required sections
        assertThat(json.get("requiredSections").toString()).contains("rules");

        // Rule fields included for rule-config
        assertThat(json.has("ruleFields")).isTrue();

        // Valid types listed
        assertThat(json.has("validDocumentTypes")).isTrue();
    }

    @Test
    void getRequiredFields_scenario_returnsOwnerAndDomain() throws Exception {
        String result = tool.getRequiredFields("scenario");
        JsonNode json = MAPPER.readTree(result);

        String typeSpecific = json.get("typeSpecificMetadata").toString();
        assertThat(typeSpecific).contains("business-domain");
        assertThat(typeSpecific).contains("owner");
    }

    @Test
    void getRequiredFields_unknownType_returnsEmpty() throws Exception {
        String result = tool.getRequiredFields("unknown-type");
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("typeSpecificMetadata").size()).isEqualTo(0);
        assertThat(json.has("typeSpecificNote")).isTrue();
    }

    // ===== Keyword rules =====

    @Test
    void getKeywordRules_enrichmentTypes_returnsAllTypes() throws Exception {
        String result = tool.getKeywordRules("enrichment-types");
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("found").asBoolean()).isTrue();
        JsonNode rules = json.get("rules");
        assertThat(rules.has("lookup-enrichment")).isTrue();
        assertThat(rules.has("calculation-enrichment")).isTrue();
        assertThat(rules.has("field-enrichment")).isTrue();
        assertThat(rules.has("conditional-mapping-enrichment")).isTrue();
    }

    @Test
    void getKeywordRules_ruleFields_returnsFields() throws Exception {
        String result = tool.getKeywordRules("rule-fields");
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("found").asBoolean()).isTrue();
        assertThat(json.get("rules").has("required")).isTrue();
        assertThat(json.get("rules").has("optional")).isTrue();
    }

    @Test
    void getKeywordRules_unknownSection_returnsAvailableSections() throws Exception {
        String result = tool.getKeywordRules("nonexistent-section");
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("found").asBoolean()).isFalse();
        assertThat(json.has("availableSections")).isTrue();
    }

    @Test
    void getKeywordRules_alwaysIncludesValidatorGaps() throws Exception {
        String result = tool.getKeywordRules("rule-fields");
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.has("validatorGaps")).isTrue();
        assertThat(json.get("validatorGaps").size()).isGreaterThan(0);
    }

    // ===== SpEL rules =====

    @Test
    void getSpelRules_returnsComprehensiveRules() throws Exception {
        String result = tool.getSpelRules();
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.has("spelRules")).isTrue();
        JsonNode spelRules = json.get("spelRules");
        assertThat(spelRules.has("field-reference")).isTrue();
        assertThat(spelRules.has("forbidden")).isTrue();
        assertThat(spelRules.has("ternary")).isTrue();
        assertThat(spelRules.has("null-check")).isTrue();
    }
}
