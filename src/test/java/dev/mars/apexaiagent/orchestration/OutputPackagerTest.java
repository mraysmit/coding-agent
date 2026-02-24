package dev.mars.apexaiagent.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.apexaiagent.orchestration.GenerationResult.GeneratedFile;
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

    // ===== Heading normalization =====

    @Test
    void normalizeSectionHeadings_markdownHashHeadings() {
        String input = """
                ### GENERATED YAML
                some yaml
                ### TEST DATA
                some data
                ### VALIDATION REPORT
                some report
                ### SUMMARY
                some summary
                """;
        String result = packager.normalizeSectionHeadings(input);
        assertThat(result).contains("=== GENERATED YAML ===");
        assertThat(result).contains("=== TEST DATA ===");
        assertThat(result).contains("=== VALIDATION REPORT ===");
        assertThat(result).contains("=== SUMMARY ===");
        assertThat(result).doesNotContain("###");
    }

    @Test
    void normalizeSectionHeadings_doubleHashHeadings() {
        String input = """
                ## GENERATED YAML
                some yaml
                ## SUMMARY
                done
                """;
        String result = packager.normalizeSectionHeadings(input);
        assertThat(result).contains("=== GENERATED YAML ===");
        assertThat(result).contains("=== SUMMARY ===");
    }

    @Test
    void normalizeSectionHeadings_alreadyCorrectFormat() {
        String input = """
                === GENERATED YAML ===
                some yaml
                === SUMMARY ===
                done
                """;
        String result = packager.normalizeSectionHeadings(input);
        assertThat(result).contains("=== GENERATED YAML ===");
        assertThat(result).contains("=== SUMMARY ===");
    }

    @Test
    void normalizeSectionHeadings_null_returnsNull() {
        assertThat(packager.normalizeSectionHeadings(null)).isNull();
    }

    @Test
    void packageOutput_markdownHeadings_extractsSections() throws Exception {
        String response = """
                ### GENERATED YAML
                ```yaml
                metadata:
                  id: "md-test"
                  name: "Markdown Test"
                  version: "1.0"
                  description: "Test markdown headings"
                  type: "rule-config"
                  author: "test"
                rules:
                  - id: "r1"
                    name: "Check Amount"
                    condition: "#amount > 100"
                    message: "High amount"
                    severity: "INFO"
                ```
                
                ### TEST DATA
                ```json
                {"amount": 500}
                ```
                
                ### VALIDATION REPORT
                - Lexical: PASS
                - Compilation: PASS
                - Execution: PASS
                - Expectations: PASS
                - Attempts: 1
                
                ### SUMMARY
                Generated rule-config with markdown headings.
                """;

        GenerationResult result = packager.packageOutput("req-md", response, 1);

        assertThat(result.success()).isTrue();
        assertThat(result.files()).hasSize(2);
        assertThat(result.validationReport().lexicalValid()).isTrue();
        assertThat(result.validationReport().compilationSuccess()).isTrue();
        assertThat(result.validationReport().executionSuccess()).isTrue();
        assertThat(result.validationReport().expectationsPass()).isTrue();
        assertThat(result.validationReport().executionDetails()).containsKey("rawReport");
        assertThat(result.summary()).contains("markdown");
    }

    // ===== Per-stage block extraction =====

    @Test
    void extractStageBlock_delimitedBlocks() {
        String text = """
                --- LEXICAL ---
                Status: PASS
                {"valid": true, "errors": [], "warnings": []}
                
                --- COMPILATION ---
                Status: PASS
                {"success": true, "message": "Compilation successful"}
                
                --- EXECUTION ---
                Status: PASS
                {"success": true, "childResults": [{"success": true}]}
                
                --- EXPECTATIONS ---
                Status: PASS
                {"overallPass": true, "totalAssertions": 2, "passed": 2, "failed": 0}
                """;

        String lexical = packager.extractStageBlock(text, "LEXICAL", "COMPILATION");
        assertThat(lexical).contains("\"valid\": true");
        assertThat(lexical).contains("Status: PASS");
        assertThat(lexical).doesNotContain("COMPILATION");

        String compilation = packager.extractStageBlock(text, "COMPILATION", "EXECUTION");
        assertThat(compilation).contains("Compilation successful");
        assertThat(compilation).doesNotContain("EXECUTION");

        String execution = packager.extractStageBlock(text, "EXECUTION", "EXPECTATIONS");
        assertThat(execution).contains("childResults");
        assertThat(execution).doesNotContain("EXPECTATIONS");

        String expectations = packager.extractStageBlock(text, "EXPECTATIONS", null);
        assertThat(expectations).contains("overallPass");
        assertThat(expectations).contains("totalAssertions");
    }

    @Test
    void extractStageBlock_fallbackToSingleLine() {
        String text = """
                - Lexical: PASS
                - Compilation: PASS
                - Execution: FAIL - rule condition error
                - Expectations: FAIL
                """;

        String lexical = packager.extractStageBlock(text, "LEXICAL", "COMPILATION");
        assertThat(lexical).contains("Lexical").contains("PASS");

        String execution = packager.extractStageBlock(text, "EXECUTION", "EXPECTATIONS");
        assertThat(execution).contains("FAIL");
    }

    @Test
    void extractStageBlock_emptyInput() {
        assertThat(packager.extractStageBlock(null, "LEXICAL", "COMPILATION")).isEmpty();
        assertThat(packager.extractStageBlock("", "LEXICAL", "COMPILATION")).isEmpty();
    }

    @Test
    void parseValidationReport_storesPerStageDetails() {
        String text = """
                --- LEXICAL ---
                Status: PASS
                {"valid": true, "errors": []}
                
                --- COMPILATION ---
                Status: PASS
                {"success": true, "message": "OK"}
                
                --- EXECUTION ---
                Status: FAIL
                {"success": false, "failureMessages": ["rule failed"]}
                
                --- EXPECTATIONS ---
                Status: FAIL
                {"overallPass": false, "failureSummary": ["mismatch"]}
                """;

        var report = packager.parseValidationReport(text);
        assertThat(report.executionDetails()).containsKey("lexicalDetail");
        assertThat(report.executionDetails()).containsKey("compilationDetail");
        assertThat(report.executionDetails()).containsKey("executionDetail");
        assertThat(report.executionDetails()).containsKey("expectationDetail");

        String lexicalDetail = (String) report.executionDetails().get("lexicalDetail");
        assertThat(lexicalDetail).contains("\"valid\": true");

        String expectationDetail = (String) report.executionDetails().get("expectationDetail");
        assertThat(expectationDetail).contains("mismatch");
    }

    // ===== drainResults integration =====

    @Test
    void packageOutput_includesDrainedRuleResults() throws Exception {
        // Simulate what ApexExecuteTool.storeResult() does on this thread
        // by calling the tool's storeResult indirectly via a real tool invocation
        // Here we test that if drainResults returns data, it ends up in executionDetails.
        //
        // Since drainResults() is thread-keyed and we can't easily mock a static method,
        // we verify the integration by manually pushing data into the collector.
        var field = dev.mars.apexaiagent.tools.ApexExecuteTool.class
                .getDeclaredField("executionResults");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var map = (java.util.concurrent.ConcurrentHashMap<Long, java.util.List<java.util.Map<String, Object>>>) field.get(null);

        long threadId = Thread.currentThread().threadId();
        var results = java.util.Collections.synchronizedList(new java.util.ArrayList<java.util.Map<String, Object>>());
        var ruleResult = new java.util.LinkedHashMap<String, Object>();
        ruleResult.put("success", true);
        ruleResult.put("resultType", "MATCH");
        ruleResult.put("childResults", java.util.List.of(
                java.util.Map.of("ruleId", "r1", "ruleName", "Rule One", "success", true,
                        "triggered", true, "resultType", "MATCH", "severity", "INFO")
        ));
        results.add(ruleResult);
        map.put(threadId, results);

        String response = """
                === GENERATED YAML ===
                metadata:
                  id: "rule-drain-test"
                  name: "Drain Test"
                  version: "1.0"
                  description: "test drain"
                  type: "rule-config"
                  author: "test"
                rules:
                  - id: "r1"
                    name: "Rule One"
                    condition: "#value > 0"
                    message: "Positive"
                
                === VALIDATION REPORT ===
                - Lexical: PASS
                - Compilation: PASS
                - Execution: PASS
                - Expectations: PASS
                
                === SUMMARY ===
                Done.
                """;

        GenerationResult result = packager.packageOutput("drain-test", response, 1);

        // Verify rule results were drained into executionDetails
        assertThat(result.validationReport().executionDetails()).containsKey("ruleResults");
        @SuppressWarnings("unchecked")
        var drainedResults = (java.util.List<java.util.Map<String, Object>>)
                result.validationReport().executionDetails().get("ruleResults");
        assertThat(drainedResults).hasSize(1);
        assertThat(drainedResults.get(0)).containsEntry("success", true);
        assertThat(drainedResults.get(0)).containsEntry("resultType", "MATCH");

        // Verify drain cleared the results
        assertThat(dev.mars.apexaiagent.tools.ApexExecuteTool.drainResults()).isEmpty();
    }
}
