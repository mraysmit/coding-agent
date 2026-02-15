package dev.mars.codingagent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.mars.codingagent.orchestration.GenerationResult.GeneratedFile;
import dev.mars.codingagent.orchestration.GenerationResult.ValidationReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Packages generated APEX artifacts into the output directory structure.
 * <p>
 * Directory convention:
 * <pre>
 * generated/apex/{request-id}/
 *   rules/
 *   scenarios/
 *   components/
 *   data/
 *   reports/
 * </pre>
 */
public class OutputPackager {

    private final Path baseOutputDir;
    private final ObjectMapper objectMapper;

    // Patterns to extract structured sections from LLM response
    private static final Pattern YAML_BLOCK = Pattern.compile(
            "===\\s*GENERATED YAML\\s*===\\s*\\n(.*?)(?=\\n===|$)", Pattern.DOTALL);
    private static final Pattern TEST_DATA_BLOCK = Pattern.compile(
            "===\\s*TEST DATA\\s*===\\s*\\n(.*?)(?=\\n===|$)", Pattern.DOTALL);
    private static final Pattern VALIDATION_BLOCK = Pattern.compile(
            "===\\s*VALIDATION REPORT\\s*===\\s*\\n(.*?)(?=\\n===|$)", Pattern.DOTALL);
    private static final Pattern SUMMARY_BLOCK = Pattern.compile(
            "===\\s*SUMMARY\\s*===\\s*\\n(.*?)$", Pattern.DOTALL);
    private static final Pattern YAML_FENCE = Pattern.compile(
            "```(?:yaml|yml)?\\s*\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern JSON_FENCE = Pattern.compile(
            "```(?:json)?\\s*\\n(.*?)```", Pattern.DOTALL);

    public OutputPackager(Path baseOutputDir) {
        this.baseOutputDir = baseOutputDir;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Parse the LLM response and package all artifacts into the output directory.
     *
     * @param requestId   unique request identifier
     * @param llmResponse the raw LLM response containing YAML, test data, and validation info
     * @param attempts    number of generation attempts taken
     * @return the GenerationResult with all file paths populated
     */
    public GenerationResult packageOutput(String requestId, String llmResponse, int attempts) throws IOException {
        Path outputDir = baseOutputDir.resolve(requestId);

        // Extract sections from the LLM response
        String yamlContent = extractSection(llmResponse, YAML_BLOCK);
        String testData = extractSection(llmResponse, TEST_DATA_BLOCK);
        String validationText = extractSection(llmResponse, VALIDATION_BLOCK);
        String summary = extractSection(llmResponse, SUMMARY_BLOCK);

        // Extract YAML from code fences if present
        yamlContent = extractFromFence(yamlContent, YAML_FENCE, yamlContent);
        testData = extractFromFence(testData, JSON_FENCE, testData);

        if (yamlContent == null || yamlContent.isBlank()) {
            // Try to extract any YAML fenced block from the full response
            yamlContent = extractFromFence(llmResponse, YAML_FENCE, null);
        }

        if (yamlContent == null || yamlContent.isBlank()) {
            return GenerationResult.failed(requestId, attempts,
                    "No YAML content found in LLM response");
        }

        // Determine doc type from YAML content
        String docType = detectDocType(yamlContent);

        // Create directory structure
        Path rulesDir = Files.createDirectories(outputDir.resolve("rules"));
        Path dataDir = Files.createDirectories(outputDir.resolve("data"));
        Path reportsDir = Files.createDirectories(outputDir.resolve("reports"));

        List<GeneratedFile> files = new ArrayList<>();

        // Write YAML file
        String yamlFileName = buildFileName(docType, requestId);
        Path yamlPath = rulesDir.resolve(yamlFileName);
        Files.writeString(yamlPath, yamlContent);
        files.add(new GeneratedFile(yamlFileName, docType, yamlContent, yamlPath));

        // Write test data if present
        if (testData != null && !testData.isBlank()) {
            String dataFileName = requestId + "-sample-input.json";
            Path dataPath = dataDir.resolve(dataFileName);
            Files.writeString(dataPath, testData);
            files.add(new GeneratedFile(dataFileName, "test-data", testData, dataPath));
        }

        // Parse validation report
        ValidationReport report = parseValidationReport(validationText);

        // Write validation report JSON
        Map<String, Object> reportJson = new LinkedHashMap<>();
        reportJson.put("requestId", requestId);
        reportJson.put("timestamp", Instant.now().toString());
        reportJson.put("attempts", attempts);
        reportJson.put("success", report.expectationsPass());
        reportJson.put("lexicalValid", report.lexicalValid());
        reportJson.put("compilationSuccess", report.compilationSuccess());
        reportJson.put("executionSuccess", report.executionSuccess());
        reportJson.put("expectationsPass", report.expectationsPass());
        reportJson.put("totalErrors", report.totalErrors());
        reportJson.put("totalWarnings", report.totalWarnings());
        reportJson.put("issues", report.issues());
        reportJson.put("summary", summary != null ? summary.trim() : "");

        Path reportPath = reportsDir.resolve("validation-report.json");
        objectMapper.writeValue(reportPath.toFile(), reportJson);

        boolean overallSuccess = report.lexicalValid() && report.compilationSuccess()
                && report.executionSuccess() && report.expectationsPass();

        return new GenerationResult(
                requestId, overallSuccess, attempts, files, report,
                summary != null ? summary.trim() : "Generation completed",
                outputDir, Instant.now()
        );
    }

    // ---- Parsing helpers ----

    String extractSection(String text, Pattern pattern) {
        if (text == null) return null;
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    String extractFromFence(String text, Pattern fencePattern, String fallback) {
        if (text == null) return fallback;
        Matcher m = fencePattern.matcher(text);
        return m.find() ? m.group(1).trim() : fallback;
    }

    String detectDocType(String yamlContent) {
        // Look for type: field at top-level or directly under metadata: (max 2-space indent)
        // Avoids matching deeply-nested type: fields in conditions or data structures
        Pattern typePattern = Pattern.compile("^\\s{0,4}type:\\s*[\"']?(\\S+?)[\"']?\\s*$", Pattern.MULTILINE);
        Matcher m = typePattern.matcher(yamlContent);
        if (m.find()) {
            return m.group(1).replace("\"", "").replace("'", "");
        }
        return "rule-config"; // default
    }

    String buildFileName(String docType, String requestId) {
        String shortId = requestId.length() > 20
                ? requestId.substring(requestId.length() - 12) : requestId;
        return switch (docType) {
            case "scenario" -> "scenario-" + shortId + ".yaml";
            case "scenario-registry" -> "scenario-registry-" + shortId + ".yaml";
            case "component" -> "component-" + shortId + ".yaml";
            case "enrichment" -> "enrichment-" + shortId + ".yaml";
            default -> "rules-" + shortId + ".yaml";
        };
    }

    ValidationReport parseValidationReport(String text) {
        if (text == null || text.isBlank()) {
            return ValidationReport.empty();
        }

        boolean lexical = text.toLowerCase().contains("lexical") && containsPass(text, "lexical");
        boolean compilation = text.toLowerCase().contains("compilation") && containsPass(text, "compilation");
        boolean execution = text.toLowerCase().contains("execution") && containsPass(text, "execution");
        boolean expectations = text.toLowerCase().contains("expectation") && containsPass(text, "expectation");

        int errors = countOccurrences(text.toLowerCase(), "fail");
        int warnings = countOccurrences(text.toLowerCase(), "warning");

        return new ValidationReport(lexical, compilation, execution, expectations,
                errors, warnings, List.of(), Map.of("rawReport", text));
    }

    private boolean containsPass(String text, String stage) {
        // Find the line containing the stage name and check if it says PASS
        String lower = text.toLowerCase();
        int idx = lower.indexOf(stage.toLowerCase());
        if (idx < 0) return false;
        int lineEnd = lower.indexOf('\n', idx);
        if (lineEnd < 0) lineEnd = lower.length();
        String line = lower.substring(idx, lineEnd);
        return line.contains("pass");
    }

    private int countOccurrences(String text, String word) {
        // Use word-boundary matching to avoid overcounting substrings
        // e.g. "fail" should not match inside "failure" or "failed"
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "\\b" + java.util.regex.Pattern.quote(word) + "\\b",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(text);
        int count = 0;
        while (m.find()) count++;
        return count;
    }
}
