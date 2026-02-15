package dev.mars.codingagent.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.codingagent.orchestration.GenerationResult.GeneratedFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for OutputPackager — parsing LLM responses and writing output files.
 */
class OutputPackagerTest {

    @TempDir
    Path tempDir;

    private OutputPackager packager;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        packager = new OutputPackager(tempDir);
    }

    // ===== Successful packaging =====

    @Test
    void packageOutput_validResponse_createsFiles() throws Exception {
        String response = """
                === GENERATED YAML ===
                ```yaml
                metadata:
                  id: "test-rules"
                  name: "Test Rules"
                  version: "1.0"
                  description: "test"
                  type: "rule-config"
                  author: "test"
                rules:
                  - id: "r1"
                    name: "Amount Check"
                    condition: "#amount > 1000"
                    message: "Too high"
                    severity: "ERROR"
                ```
                
                === TEST DATA ===
                ```json
                {"amount": 5000}
                ```
                
                === VALIDATION REPORT ===
                - Lexical: PASS
                - Compilation: PASS
                - Execution: PASS
                - Expectations: PASS
                - Attempts: 1
                
                === SUMMARY ===
                Generated a simple rule-config that validates amount limits.
                """;

        GenerationResult result = packager.packageOutput("req-123", response, 1);

        assertThat(result.success()).isTrue();
        assertThat(result.files()).hasSize(2); // YAML + test data
        assertThat(result.validationReport().lexicalValid()).isTrue();
        assertThat(result.validationReport().compilationSuccess()).isTrue();
        assertThat(result.validationReport().executionSuccess()).isTrue();
        assertThat(result.validationReport().expectationsPass()).isTrue();
        assertThat(result.summary()).contains("rule-config");
    }

    @Test
    void packageOutput_writesYamlFile() throws Exception {
        String response = """
                === GENERATED YAML ===
                metadata:
                  id: "x"
                  name: "X"
                  version: "1.0"
                  description: "x"
                  type: "rule-config"
                  author: "x"
                
                === VALIDATION REPORT ===
                - Lexical: PASS
                - Compilation: PASS
                - Execution: PASS
                - Expectations: PASS
                
                === SUMMARY ===
                Done.
                """;

        GenerationResult result = packager.packageOutput("req-456", response, 1);

        assertThat(result.files()).isNotEmpty();
        GeneratedFile yamlFile = result.files().get(0);
        assertThat(yamlFile.fileName()).endsWith(".yaml");
        assertThat(yamlFile.writtenTo()).isNotNull();
        assertThat(Files.exists(yamlFile.writtenTo())).isTrue();
        assertThat(Files.readString(yamlFile.writtenTo())).contains("rule-config");
    }

    @Test
    void packageOutput_writesValidationReport() throws Exception {
        String response = """
                === GENERATED YAML ===
                metadata:
                  id: "x"
                  type: "rule-config"
                
                === VALIDATION REPORT ===
                - Lexical: PASS
                - Compilation: FAIL
                
                === SUMMARY ===
                Partial.
                """;

        GenerationResult result = packager.packageOutput("req-789", response, 2);

        Path reportPath = tempDir.resolve("req-789").resolve("reports").resolve("validation-report.json");
        assertThat(Files.exists(reportPath)).isTrue();

        JsonNode report = mapper.readTree(Files.readString(reportPath));
        assertThat(report.get("requestId").asText()).isEqualTo("req-789");
        assertThat(report.get("attempts").asInt()).isEqualTo(2);
    }

    @Test
    void packageOutput_createsDirectoryStructure() throws Exception {
        String response = """
                === GENERATED YAML ===
                metadata:
                  id: "dir-test"
                  type: "scenario"
                
                === SUMMARY ===
                Test.
                """;

        packager.packageOutput("req-dir", response, 1);

        assertThat(Files.isDirectory(tempDir.resolve("req-dir").resolve("rules"))).isTrue();
        assertThat(Files.isDirectory(tempDir.resolve("req-dir").resolve("data"))).isTrue();
        assertThat(Files.isDirectory(tempDir.resolve("req-dir").resolve("reports"))).isTrue();
    }

    // ===== Parsing edge cases =====

    @Test
    void packageOutput_noYaml_returnsFailed() throws Exception {
        String response = "Here is some text without any YAML content.";

        GenerationResult result = packager.packageOutput("req-empty", response, 1);

        assertThat(result.success()).isFalse();
        assertThat(result.summary()).contains("No YAML");
    }

    @Test
    void packageOutput_yamlInFence_extractsCorrectly() throws Exception {
        String response = """
                Some preamble text.
                
                === GENERATED YAML ===
                ```yaml
                metadata:
                  id: "fenced"
                  name: "Fenced"
                  version: "1.0"
                  type: "rule-config"
                ```
                
                === SUMMARY ===
                Extracted from fence.
                """;

        GenerationResult result = packager.packageOutput("req-fence", response, 1);

        assertThat(result.files()).isNotEmpty();
        assertThat(result.files().get(0).content()).contains("fenced");
        assertThat(result.files().get(0).content()).doesNotContain("```");
    }

    @Test
    void packageOutput_noTestData_onlyYamlFile() throws Exception {
        String response = """
                === GENERATED YAML ===
                metadata:
                  id: "no-data"
                  type: "rule-config"
                
                === SUMMARY ===
                No test data.
                """;

        GenerationResult result = packager.packageOutput("req-nodata", response, 1);

        assertThat(result.files()).hasSize(1); // only YAML, no test data
        assertThat(result.files().get(0).docType()).isEqualTo("rule-config");
    }

    // ===== Doc type detection =====

    @Test
    void detectDocType_ruleConfig() {
        String yaml = """
                metadata:
                  type: "rule-config"
                """;
        assertThat(packager.detectDocType(yaml)).isEqualTo("rule-config");
    }

    @Test
    void detectDocType_scenario() {
        String yaml = """
                metadata:
                  type: scenario
                """;
        assertThat(packager.detectDocType(yaml)).isEqualTo("scenario");
    }

    @Test
    void detectDocType_missing_defaultsToRuleConfig() {
        String yaml = """
                metadata:
                  id: "no-type"
                """;
        assertThat(packager.detectDocType(yaml)).isEqualTo("rule-config");
    }

    // ===== File naming =====

    @Test
    void buildFileName_ruleConfig() {
        assertThat(packager.buildFileName("rule-config", "req-123")).contains("rules-");
        assertThat(packager.buildFileName("rule-config", "req-123")).endsWith(".yaml");
    }

    @Test
    void buildFileName_scenario() {
        assertThat(packager.buildFileName("scenario", "req-456")).startsWith("scenario-");
    }

    @Test
    void buildFileName_component() {
        assertThat(packager.buildFileName("component", "req-789")).startsWith("component-");
    }

    // ===== Validation report parsing =====

    @Test
    void parseValidationReport_allPass() {
        String text = """
                - Lexical: PASS
                - Compilation: PASS
                - Execution: PASS
                - Expectations: PASS
                """;

        var report = packager.parseValidationReport(text);
        assertThat(report.lexicalValid()).isTrue();
        assertThat(report.compilationSuccess()).isTrue();
        assertThat(report.executionSuccess()).isTrue();
        assertThat(report.expectationsPass()).isTrue();
    }

    @Test
    void parseValidationReport_mixedResults() {
        String text = """
                - Lexical: PASS
                - Compilation: PASS
                - Execution: FAIL - rule condition error
                - Expectations: FAIL
                """;

        var report = packager.parseValidationReport(text);
        assertThat(report.lexicalValid()).isTrue();
        assertThat(report.compilationSuccess()).isTrue();
        assertThat(report.executionSuccess()).isFalse();
        assertThat(report.expectationsPass()).isFalse();
    }

    @Test
    void parseValidationReport_null_returnsEmpty() {
        var report = packager.parseValidationReport(null);
        assertThat(report.lexicalValid()).isFalse();
        assertThat(report.compilationSuccess()).isFalse();
    }

    // ===== Section extraction =====

    @Test
    void extractSection_findsSummary() {
        String text = """
                === GENERATED YAML ===
                some yaml
                
                === SUMMARY ===
                This is the summary.
                """;

        String summary = packager.extractSection(text,
                java.util.regex.Pattern.compile("===\\s*SUMMARY\\s*===\\s*\\n(.*?)$",
                        java.util.regex.Pattern.DOTALL));
        assertThat(summary).isEqualTo("This is the summary.");
    }

    @Test
    void extractFromFence_extractsContent() {
        String text = """
                ```yaml
                id: test
                name: test
                ```
                """;

        String content = packager.extractFromFence(text,
                java.util.regex.Pattern.compile("```(?:yaml|yml)?\\s*\\n(.*?)```",
                        java.util.regex.Pattern.DOTALL), null);
        assertThat(content).contains("id: test");
        assertThat(content).doesNotContain("```");
    }
}
