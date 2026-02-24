package dev.mars.apexaiagent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.mars.apexaiagent.orchestration.GenerationResult.GeneratedFile;
import dev.mars.apexaiagent.orchestration.GenerationResult.ValidationReport;
import dev.mars.apexaiagent.tools.ApexExecuteTool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(OutputPackager.class);

    private final Path baseOutputDir;
    private final ObjectMapper objectMapper;

    // Patterns to extract structured sections from LLM response
    // These work on text that has already been normalized by normalizeSectionHeadings()
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
        log.debug("Packaging output for request {} (attempt {}). Response length: {} chars",
                requestId, attempts, llmResponse != null ? llmResponse.length() : 0);
        Path outputDir = baseOutputDir.resolve(requestId);

        // Normalize section headings: LLMs may use "### HEADING" or "## HEADING"
        // instead of the expected "=== HEADING ===" format
        String normalizedResponse = normalizeSectionHeadings(llmResponse);

        // Extract sections from the normalized response
        String yamlContent = extractSection(normalizedResponse, YAML_BLOCK);
        String testData = extractSection(normalizedResponse, TEST_DATA_BLOCK);
        String validationText = extractSection(normalizedResponse, VALIDATION_BLOCK);
        String summary = extractSection(normalizedResponse, SUMMARY_BLOCK);

        log.debug("Extracted sections — YAML: {}, testData: {}, validation: {}, summary: {}",
                yamlContent != null ? yamlContent.length() + " chars" : "NOT FOUND",
                testData != null ? testData.length() + " chars" : "NOT FOUND",
                validationText != null ? validationText.length() + " chars" : "NOT FOUND",
                summary != null ? summary.length() + " chars" : "NOT FOUND");

        // Extract YAML from code fences if present
        yamlContent = extractFromFence(yamlContent, YAML_FENCE, yamlContent);
        testData = extractFromFence(testData, JSON_FENCE, testData);

        if (yamlContent == null || yamlContent.isBlank()) {
            // Try to extract any YAML fenced block from the full response
            yamlContent = extractFromFence(normalizedResponse, YAML_FENCE, null);
            log.debug("Fallback fence extraction: {}",
                    yamlContent != null ? yamlContent.length() + " chars" : "NOT FOUND");
        }

        if (yamlContent == null || yamlContent.isBlank()) {
            log.warn("No YAML content found in LLM response for request {}", requestId);
            return GenerationResult.failed(requestId, attempts,
                    "No YAML content found in LLM response");
        }

        // Determine doc type from YAML content
        String docType = detectDocType(yamlContent);
        log.debug("Detected document type: '{}'. YAML preview: {}...", docType,
                yamlContent.substring(0, Math.min(200, yamlContent.length())));

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
        log.debug("Wrote YAML file: {} ({} chars)", yamlPath, yamlContent.length());

        // Write test data if present
        if (testData != null && !testData.isBlank()) {
            String dataFileName = requestId + "-sample-input.json";
            Path dataPath = dataDir.resolve(dataFileName);
            Files.writeString(dataPath, testData);
            files.add(new GeneratedFile(dataFileName, "test-data", testData, dataPath));
            log.debug("Wrote test data file: {} ({} chars)", dataPath, testData.length());
        }

        // Parse validation report
        ValidationReport report = parseValidationReport(validationText);

        // Drain captured execution results from the tool (same thread)
        List<Map<String, Object>> capturedRuleResults = ApexExecuteTool.drainResults();
        if (!capturedRuleResults.isEmpty()) {
            log.debug("Drained {} captured execution results from ApexExecuteTool", capturedRuleResults.size());
            // Merge into executionDetails
            Map<String, Object> mergedDetails = new LinkedHashMap<>(report.executionDetails());
            mergedDetails.put("ruleResults", capturedRuleResults);
            report = new ValidationReport(
                    report.lexicalValid(), report.compilationSuccess(),
                    report.executionSuccess(), report.expectationsPass(),
                    report.totalErrors(), report.totalWarnings(),
                    report.issues(), mergedDetails);
        }

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
        log.debug("Wrote validation report: {}", reportPath);

        boolean overallSuccess = report.lexicalValid() && report.compilationSuccess()
                && report.executionSuccess() && report.expectationsPass();
        log.info("Packaging complete for request {}. Success={}, files={}, dir={}",
                requestId, overallSuccess, files.size(), outputDir);

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
            log.debug("No validation report text to parse — returning empty report");
            return ValidationReport.empty();
        }

        // Extract per-stage detail blocks (delimited by --- STAGE --- markers)
        String lexicalBlock = extractStageBlock(text, "LEXICAL", "COMPILATION");
        String compilationBlock = extractStageBlock(text, "COMPILATION", "EXECUTION");
        String executionBlock = extractStageBlock(text, "EXECUTION", "EXPECTATIONS");
        String expectationBlock = extractStageBlock(text, "EXPECTATIONS", null);

        // Determine pass/fail from per-stage blocks (preferred) or falling back to full text
        boolean lexical = containsPass(lexicalBlock.isEmpty() ? text : lexicalBlock, "lexical");
        boolean compilation = containsPass(compilationBlock.isEmpty() ? text : compilationBlock, "compilation");
        boolean execution = containsPass(executionBlock.isEmpty() ? text : executionBlock, "execution");
        boolean expectations = containsPass(expectationBlock.isEmpty() ? text : expectationBlock, "expectation");

        int errors = countOccurrences(text.toLowerCase(), "fail");
        int warnings = countOccurrences(text.toLowerCase(), "warning");
        log.debug("Parsed validation report — lexical={}, compilation={}, execution={}, expectations={}, errors={}, warnings={}",
                lexical, compilation, execution, expectations, errors, warnings);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rawReport", text);
        details.put("lexicalDetail", lexicalBlock);
        details.put("compilationDetail", compilationBlock);
        details.put("executionDetail", executionBlock);
        details.put("expectationDetail", expectationBlock);

        return new ValidationReport(lexical, compilation, execution, expectations,
                errors, warnings, List.of(), details);
    }

    /**
     * Extracts a stage detail block from the validation report text.
     * Looks for "--- STAGE ---" markers; falls back to "- Stage:" or "Stage:" lines.
     */
    String extractStageBlock(String text, String stage, String nextStage) {
        if (text == null || text.isBlank()) return "";

        // Try --- STAGE --- delimited blocks first
        String marker = "--- " + stage + " ---";
        int start = text.indexOf(marker);
        if (start < 0) {
            // Fallback: try case-insensitive
            String lower = text.toLowerCase();
            String lowerMarker = marker.toLowerCase();
            start = lower.indexOf(lowerMarker);
        }

        if (start >= 0) {
            start += marker.length();
            // Skip any leading newline
            if (start < text.length() && text.charAt(start) == '\n') start++;

            int end = text.length();
            if (nextStage != null) {
                String nextMarker = "--- " + nextStage + " ---";
                int nextIdx = text.indexOf(nextMarker, start);
                if (nextIdx < 0) {
                    nextIdx = text.toLowerCase().indexOf(nextMarker.toLowerCase(), start);
                }
                if (nextIdx > 0) {
                    end = nextIdx;
                }
            }
            // Also stop at "Attempts:" or "=== SUMMARY ===" if before end
            int attemptsIdx = text.toLowerCase().indexOf("attempts:", start);
            if (attemptsIdx > 0 && attemptsIdx < end) {
                end = attemptsIdx;
            }

            String block = text.substring(start, end).trim();
            if (!block.isEmpty()) return stripCodeFences(block);
        }

        // Fallback: extract the single line like "- Lexical: PASS" or "Lexical: PASS"
        String lower = text.toLowerCase();
        int idx = lower.indexOf(stage.toLowerCase());
        if (idx >= 0) {
            int lineStart = text.lastIndexOf('\n', idx);
            lineStart = lineStart < 0 ? 0 : lineStart + 1;
            int lineEnd = text.indexOf('\n', idx);
            if (lineEnd < 0) lineEnd = text.length();
            return text.substring(lineStart, lineEnd).trim();
        }

        return "";
    }

    private boolean containsPass(String text, String stage) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase();

        // First: look for "Status: PASS" anywhere in the block
        if (lower.contains("status:") && lower.contains("pass")) {
            // Make sure PASS appears on a "Status:" line, not just anywhere
            for (String line : lower.split("\n")) {
                if (line.trim().startsWith("status:") && line.contains("pass")) {
                    return true;
                }
            }
        }

        // Second: look for "valid": true or "success": true in JSON content
        if (lower.contains("\"valid\": true") || lower.contains("\"valid\":true")) return true;
        if (lower.contains("\"success\": true") || lower.contains("\"success\":true")) return true;
        if (lower.contains("\"overallpass\": true") || lower.contains("\"overallpass\":true")) return true;

        // Third: fallback — look for "pass" on the same line as the stage keyword
        int idx = lower.indexOf(stage.toLowerCase());
        if (idx >= 0) {
            int lineEnd = lower.indexOf('\n', idx);
            if (lineEnd < 0) lineEnd = lower.length();
            String line = lower.substring(idx, lineEnd);
            if (line.contains("pass")) return true;
        }

        return false;
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

    /**
     * Normalizes section headings from various LLM formats to the canonical === FORMAT ===.
     * Handles:
     * - ### GENERATED YAML  → === GENERATED YAML ===
     * - ## VALIDATION REPORT → === VALIDATION REPORT ===
     * - **TEST DATA**        → === TEST DATA ===
     */
    String normalizeSectionHeadings(String text) {
        if (text == null) return null;
        // Match markdown headings (##, ###, ####) or bold (**) for known section names
        return text.replaceAll(
                "(?m)^(?:#{1,4}\\s*|\\*{2})(GENERATED YAML|TEST DATA|VALIDATION REPORT|SUMMARY)(?:\\s*\\*{2})?\\s*$",
                "=== $1 ===");
    }

    /**
     * Strips markdown code fences (```json ... ```) from text,
     * leaving the content inside the fences intact.
     */
    String stripCodeFences(String text) {
        if (text == null) return null;
        return text.replaceAll("(?m)^```[a-zA-Z]*\\s*$", "").trim();
    }
}
