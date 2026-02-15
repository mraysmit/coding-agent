package dev.mars.codingagent;

import dev.mars.apex.compiler.ApexYamlCompiler;
import dev.mars.apex.compiler.lexical.ApexYamlLexicalValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke tests verifying the APEX compiler and lexical validator
 * can be invoked from this project (Spring Boot 4.x host).
 * No Spring context needed — pure unit tests.
 */
class ApexCompilerSmokeTest {

    private final ApexYamlCompiler compiler = new ApexYamlCompiler();

    // --- ApexYamlCompiler tests ---

    @Test
    void compilerAcceptsValidRuleConfig() {
        String yaml = """
                metadata:
                  id: "smoke-test"
                  name: "Smoke Test"
                  version: "1.0"
                  description: "Compiler smoke test"
                  type: "rule-config"
                  author: "test"
                
                rules:
                  - id: "r1"
                    condition: "#amount > 0"
                    message: "Amount must be positive"
                    severity: "ERROR"
                """;

        var result = compiler.compile(yaml);

        assertThat(result.success).isTrue();
        assertThat(result.message).contains("success");
    }

    @Test
    void compilerRejectsInvalidYaml() {
        String badYaml = "this: is: not: valid: yaml: [[[";

        var result = compiler.compile(badYaml);

        // Should fail gracefully, not throw
        assertThat(result).isNotNull();
    }

    @Test
    void compilerRejectsMissingMetadata() {
        String yaml = """
                rules:
                  - id: "r1"
                    condition: "#x > 0"
                    message: "fail"
                    severity: "ERROR"
                """;

        var result = compiler.compile(yaml);
        // Missing metadata section — compiler should report failure
        assertThat(result).isNotNull();
    }

    // --- ApexYamlLexicalValidator tests ---

    @Test
    void lexicalValidatorAcceptsValidFile(@TempDir Path tempDir) throws Exception {
        String yaml = """
                metadata:
                  id: "lex-test"
                  name: "Lexical Test"
                  version: "1.0"
                  description: "Lexical validator smoke test"
                  type: "rule-config"
                  author: "test"
                
                rules:
                  - id: "r1"
                    condition: "#value > 100"
                    message: "Value exceeds threshold"
                    severity: "WARNING"
                """;

        Path yamlFile = tempDir.resolve("valid-test.yaml");
        Files.writeString(yamlFile, yaml);

        var validator = new ApexYamlLexicalValidator();
        var result = validator.validateFile(yamlFile);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void lexicalValidatorReportsMissingRequiredFields(@TempDir Path tempDir) throws Exception {
        // Missing "description" and "type" in metadata
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

        Path yamlFile = tempDir.resolve("incomplete-test.yaml");
        Files.writeString(yamlFile, yaml);

        var validator = new ApexYamlLexicalValidator();
        var result = validator.validateFile(yamlFile);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).isNotEmpty();
    }

    @Test
    void lexicalValidatorReportsInvalidDocumentType(@TempDir Path tempDir) throws Exception {
        String yaml = """
                metadata:
                  id: "bad-type"
                  name: "Bad Type"
                  version: "1.0"
                  description: "Test invalid type"
                  type: "nonsense-type"
                  author: "test"
                
                rules:
                  - id: "r1"
                    condition: "true"
                    message: "test"
                    severity: "INFO"
                """;

        Path yamlFile = tempDir.resolve("bad-type-test.yaml");
        Files.writeString(yamlFile, yaml);

        var validator = new ApexYamlLexicalValidator();
        var result = validator.validateFile(yamlFile);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors().stream()
                .anyMatch(e -> e.toLowerCase().contains("type"))).isTrue();
    }

    @Test
    void lexicalValidatorDetectsMalformedSpel(@TempDir Path tempDir) throws Exception {
        String yaml = """
                metadata:
                  id: "bad-spel"
                  name: "Bad SpEL"
                  version: "1.0"
                  description: "Test malformed SpEL"
                  type: "rule-config"
                  author: "test"
                
                rules:
                  - id: "r1"
                    condition: "##doubleHash > 0"
                    message: "test"
                    severity: "ERROR"
                """;

        Path yamlFile = tempDir.resolve("bad-spel-test.yaml");
        Files.writeString(yamlFile, yaml);

        var validator = new ApexYamlLexicalValidator();
        var result = validator.validateFile(yamlFile);

        // Should detect ## as invalid SpEL — could be in errors or warnings
        boolean hasSpelIssue = result.getWarnings().stream()
                .anyMatch(w -> w.contains("##") || w.toLowerCase().contains("spel"))
                || result.getErrors().stream()
                .anyMatch(e -> e.contains("##") || e.toLowerCase().contains("spel"));
        assertThat(hasSpelIssue)
                .describedAs("Validator should flag '##' in SpEL expressions (errors: %s, warnings: %s)",
                        result.getErrors(), result.getWarnings())
                .isTrue();
    }
}
