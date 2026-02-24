package dev.mars.apexaiagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for ApexCompileTool — lexical validation, compilation, and combined validate-and-compile.
 */
class ApexCompileToolTest {

    private static ApexCompileTool tool;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void setUp() {
        tool = ApexCompileTool.builder().build();
    }

    // ===== Lexical Validation =====

    @Test
    void validateLexical_validRuleConfig_returnsValid() throws Exception {
        String yaml = """
                metadata:
                  id: "test-1"
                  name: "Test"
                  version: "1.0"
                  description: "Test rule"
                  type: "rule-config"
                  author: "test"
                rules:
                  - id: "r1"
                    condition: "#amount > 100"
                    message: "High amount"
                    severity: "ERROR"
                """;

        String result = tool.validateLexical(yaml);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("valid").asBoolean()).isTrue();
        assertThat(json.get("errors").size()).isEqualTo(0);
        assertThat(json.get("errorCount").asInt()).isEqualTo(0);
    }

    @Test
    void validateLexical_missingMetadataFields_returnsErrors() throws Exception {
        String yaml = """
                metadata:
                  id: "incomplete"
                  name: "Incomplete"
                  version: "1.0"
                rules:
                  - id: "r1"
                    condition: "#x > 0"
                    message: "test"
                    severity: "ERROR"
                """;

        String result = tool.validateLexical(yaml);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("valid").asBoolean()).isFalse();
        assertThat(json.get("errors").size()).isGreaterThan(0);
        assertThat(json.has("errorClassification")).isTrue();
    }

    @Test
    void validateLexical_invalidDocType_returnsErrors() throws Exception {
        String yaml = """
                metadata:
                  id: "bad-type"
                  name: "Bad"
                  version: "1.0"
                  description: "test"
                  type: "nonsense"
                  author: "test"
                rules:
                  - id: "r1"
                    condition: "true"
                    message: "test"
                    severity: "INFO"
                """;

        String result = tool.validateLexical(yaml);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("valid").asBoolean()).isFalse();
        assertThat(json.get("errors").toString()).containsIgnoringCase("type");
    }

    @Test
    void validateLexical_doubleHash_flagsSpelIssue() throws Exception {
        String yaml = """
                metadata:
                  id: "spel"
                  name: "SpEL"
                  version: "1.0"
                  description: "test"
                  type: "rule-config"
                  author: "test"
                rules:
                  - id: "r1"
                    condition: "##badHash > 0"
                    message: "test"
                    severity: "ERROR"
                """;

        String result = tool.validateLexical(yaml);
        JsonNode json = MAPPER.readTree(result);

        // double hash should appear in errors or warnings
        String allMessages = json.get("errors").toString() + json.get("warnings").toString();
        assertThat(allMessages).containsIgnoringCase("double hash");
    }

    @Test
    void validateLexical_invalidYamlSyntax_handlesGracefully() throws Exception {
        String badYaml = "this: is: not: valid: [[[";

        String result = tool.validateLexical(badYaml);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("valid").asBoolean()).isFalse();
        assertThat(json.get("errorCount").asInt()).isGreaterThan(0);
    }

    // ===== Compilation =====

    @Test
    void compile_validYaml_returnsSuccess() throws Exception {
        String yaml = """
                metadata:
                  id: "compile-test"
                  name: "Compile Test"
                  version: "1.0"
                  description: "test"
                  type: "rule-config"
                  author: "test"
                rules:
                  - id: "r1"
                    condition: "#x > 0"
                    message: "positive"
                    severity: "ERROR"
                """;

        String result = tool.compile(yaml);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("success").asBoolean()).isTrue();
        assertThat(json.get("message").asText()).containsIgnoringCase("success");
    }

    @Test
    void compile_returnsStructuredJson() throws Exception {
        String yaml = """
                metadata:
                  id: "x"
                  name: "X"
                  version: "1.0"
                  description: "x"
                  type: "rule-config"
                  author: "x"
                rules:
                  - id: "r1"
                    condition: "true"
                    message: "ok"
                    severity: "INFO"
                """;

        String result = tool.compile(yaml);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.has("success")).isTrue();
        assertThat(json.has("message")).isTrue();
    }

    // ===== Combined Validate and Compile =====

    @Test
    void validateAndCompile_validYaml_passesAllStages() throws Exception {
        String yaml = """
                metadata:
                  id: "combined"
                  name: "Combined"
                  version: "1.0"
                  description: "combined test"
                  type: "rule-config"
                  author: "test"
                rules:
                  - id: "r1"
                    condition: "#value > 0"
                    message: "positive"
                    severity: "WARNING"
                """;

        String result = tool.validateAndCompile(yaml);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("overallSuccess").asBoolean()).isTrue();
        assertThat(json.get("stoppedAt").asText()).isEqualTo("none");
        assertThat(json.has("lexical")).isTrue();
        assertThat(json.has("compilation")).isTrue();
    }

    @Test
    void validateAndCompile_lexicalFailure_stopsBeforeCompile() throws Exception {
        String yaml = """
                metadata:
                  id: "bad"
                  name: "Bad"
                  version: "1.0"
                rules:
                  - id: "r1"
                    condition: "true"
                    message: "test"
                    severity: "ERROR"
                """;

        String result = tool.validateAndCompile(yaml);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.get("overallSuccess").asBoolean()).isFalse();
        assertThat(json.get("stoppedAt").asText()).isEqualTo("lexical");
        assertThat(json.has("compilation")).isFalse();
        assertThat(json.has("suggestion")).isTrue();
    }

    // ===== Error classification =====

    @Test
    void errorClassification_containsErrorCodes() throws Exception {
        String yaml = """
                metadata:
                  id: "classify"
                  version: "1.0"
                """;

        String result = tool.validateLexical(yaml);
        JsonNode json = MAPPER.readTree(result);

        assertThat(json.has("errorClassification")).isTrue();
        JsonNode classifications = json.get("errorClassification");
        assertThat(classifications.isArray()).isTrue();
        for (JsonNode c : classifications) {
            assertThat(c.has("errorCode")).isTrue();
            assertThat(c.has("message")).isTrue();
            String code = c.get("errorCode").asText();
            assertThat(code).startsWith("E_");
        }
    }
}
