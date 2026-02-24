package dev.mars.apexaiagent.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ApexKnowledgeIngester.
 * Tests markdown chunking, YAML processing, and feature detection
 * without requiring a real embedding model or vector store.
 */
class ApexKnowledgeIngesterTest {

    @TempDir
    Path tempDir;

    ApexKnowledgeIngester ingester;

    @BeforeEach
    void setUp() {
        EmbeddingModel mockEmbeddingModel = mock(EmbeddingModel.class);
        ingester = ApexKnowledgeIngester.builder()
                .embeddingModel(mockEmbeddingModel)
                .apexProjectRoot(tempDir)
                .vectorStorePath(tempDir.resolve("vector-store.json"))
                .build();
    }

    // ---- Markdown Chunking Tests ----

    @Test
    void chunkMarkdownFile_splitsByHeadings() throws IOException {
        String content = """
                # Main Title
                Introduction text.

                ## Section A
                Content for section A with details.

                ## Section B
                Content for section B.

                ### Subsection B1
                Deeper content.
                """;
        Path mdFile = tempDir.resolve("test.md");
        Files.writeString(mdFile, content);

        List<Document> chunks = ingester.chunkMarkdownFile(mdFile);

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(3);
        // Each chunk should have its heading
        List<String> texts = chunks.stream().map(Document::getText).toList();
        assertThat(texts).anyMatch(t -> t.contains("Section A") && t.contains("Content for section A"));
        assertThat(texts).anyMatch(t -> t.contains("Section B") && t.contains("Content for section B"));
    }

    @Test
    void chunkMarkdownFile_singleFileWithoutHeadings() throws IOException {
        String content = "Just a paragraph of text without any headings.";
        Path mdFile = tempDir.resolve("simple.md");
        Files.writeString(mdFile, content);

        List<Document> chunks = ingester.chunkMarkdownFile(mdFile);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().getText()).contains("Just a paragraph");
    }

    @Test
    void chunkMarkdownFile_metadataIncludesSource() throws IOException {
        String content = """
                ## Overview
                Some content here.
                """;
        Path mdFile = tempDir.resolve("docs").resolve("guide.md");
        Files.createDirectories(mdFile.getParent());
        Files.writeString(mdFile, content);

        List<Document> chunks = ingester.chunkMarkdownFile(mdFile);

        assertThat(chunks).isNotEmpty();
        Document doc = chunks.getFirst();
        assertThat(doc.getMetadata()).containsKey("source");
        assertThat(doc.getMetadata().get("source").toString()).contains("docs/guide.md");
        assertThat(doc.getMetadata().get("contentType")).isEqualTo("documentation");
    }

    // ---- YAML Processing Tests ----

    @Test
    void processYamlFile_extractsMetadata() throws IOException {
        String yaml = """
                # Purpose: Test credit scoring rules
                metadata:
                  id: credit-scoring-001
                  name: Credit Scoring Rules
                  version: "1.0"
                  type: rule-config
                  description: Rules for credit scoring
                  author: test-author
                rules:
                  - id: score-check
                    name: Minimum Score
                    condition: "#score >= 650"
                    message: Credit score meets minimum
                    severity: INFO
                """;
        Path yamlFile = tempDir.resolve("examples").resolve("credit-scoring.yaml");
        Files.createDirectories(yamlFile.getParent());
        Files.writeString(yamlFile, yaml);

        Document doc = ingester.processYamlFile(yamlFile);

        assertThat(doc).isNotNull();
        assertThat(doc.getText()).contains("Credit Scoring Rules");
        assertThat(doc.getText()).contains("rule-config");
        assertThat(doc.getText()).contains("Test credit scoring rules");

        var meta = doc.getMetadata();
        assertThat(meta.get("contentType")).isEqualTo("yaml-example");
        assertThat(meta.get("docType")).isEqualTo("rule-config");
        assertThat(meta.get("yamlId")).isEqualTo("credit-scoring-001");
        assertThat(meta.get("source").toString()).contains("examples/credit-scoring.yaml");
    }

    @Test
    void processYamlFile_detectsFeatures() throws IOException {
        String yaml = """
                metadata:
                  id: complex-001
                  name: Complex Config
                  type: rule-config
                rules:
                  - id: r1
                    name: Rule 1
                    condition: "#value > 0"
                    severity: WARNING
                enrichments:
                  - id: e1
                    name: Enrich 1
                rule-groups:
                  - id: rg1
                    name: Group 1
                """;
        Path yamlFile = tempDir.resolve("complex.yaml");
        Files.writeString(yamlFile, yaml);

        Document doc = ingester.processYamlFile(yamlFile);

        assertThat(doc).isNotNull();
        String features = (String) doc.getMetadata().get("features");
        assertThat(features).contains("rules");
        assertThat(features).contains("enrichments");
        assertThat(features).contains("rule-groups");
    }

    @Test
    void processYamlFile_blankFileReturnsNull() throws IOException {
        Path yamlFile = tempDir.resolve("empty.yaml");
        Files.writeString(yamlFile, "   ");

        Document doc = ingester.processYamlFile(yamlFile);

        assertThat(doc).isNull();
    }

    // ---- Feature Detection Tests ----

    @Test
    void detectFeatures_identifiesAllKnownFeatures() {
        String content = """
                rules:
                enrichments:
                rule-groups:
                enrichment-groups:
                data-sources:
                scenario:
                pipeline:
                components:
                lookup:
                transformation:
                error-recovery:
                field-mappings:
                severity: INFO
                condition: "#x > 1"
                """;

        List<String> features = ingester.detectFeatures(content);

        assertThat(features).contains("rules", "enrichments", "rule-groups",
                "enrichment-groups", "data-sources", "scenario", "pipeline",
                "components", "lookup", "transformations", "error-recovery",
                "field-mappings", "severity", "conditions");
    }

    @Test
    void detectFeatures_emptyContentReturnsEmptyList() {
        List<String> features = ingester.detectFeatures("");
        assertThat(features).isEmpty();
    }

    // ---- Heading Splitter Tests ----

    @Test
    void splitByHeadings_correctSectionCount() {
        String markdown = """
                # Title
                Intro.

                ## Section 1
                Content 1.

                ## Section 2
                Content 2.

                ### Sub 2.1
                Sub content.
                """;

        List<ApexKnowledgeIngester.Section> sections = ingester.splitByHeadings(markdown);

        assertThat(sections).hasSizeGreaterThanOrEqualTo(3);
        assertThat(sections.getFirst().heading()).contains("Title");
    }

    @Test
    void splitByHeadings_noHeadingsReturnsEmpty() {
        String markdown = "Just some plain text without headings.";
        List<ApexKnowledgeIngester.Section> sections = ingester.splitByHeadings(markdown);
        assertThat(sections).isEmpty();
    }

    // ---- Directory Scanning Tests ----

    @Test
    void ingestMarkdownDirectory_findsAllMdFiles() throws IOException {
        Path docsDir = tempDir.resolve("docs");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("guide1.md"), "## Guide 1\nContent 1.");
        Files.writeString(docsDir.resolve("guide2.md"), "## Guide 2\nContent 2.");
        Files.writeString(docsDir.resolve("readme.txt"), "Not a markdown file.");

        List<Document> docs = ingester.ingestMarkdownDirectory(docsDir);

        assertThat(docs).hasSize(2);
    }

    @Test
    void ingestYamlDirectory_findsAllYamlFiles() throws IOException {
        Path examplesDir = tempDir.resolve("examples");
        Files.createDirectories(examplesDir);
        Files.writeString(examplesDir.resolve("rule1.yaml"), """
                metadata:
                  id: r1
                  name: Rule 1
                  type: rule-config
                """);
        Files.writeString(examplesDir.resolve("rule2.yml"), """
                metadata:
                  id: r2
                  name: Rule 2
                  type: rule-config
                """);
        Files.writeString(examplesDir.resolve("data.json"), "{}");

        List<Document> docs = ingester.ingestYamlDirectory(examplesDir);

        assertThat(docs).hasSize(2);
    }

    @Test
    void ingestYamlDirectory_emptyReturnsEmptyList() throws IOException {
        Path emptyDir = tempDir.resolve("empty");
        Files.createDirectories(emptyDir);

        List<Document> docs = ingester.ingestYamlDirectory(emptyDir);

        assertThat(docs).isEmpty();
    }

    // ---- Builder Tests ----

    @Test
    void builder_requiresEmbeddingModel() {
        assertThatIllegalStateException()
                .isThrownBy(() -> ApexKnowledgeIngester.builder().build())
                .withMessageContaining("embeddingModel is required");
    }

    @Test
    void builder_appliesDefaults() {
        EmbeddingModel mockModel = mock(EmbeddingModel.class);
        ApexKnowledgeIngester built = ApexKnowledgeIngester.builder()
                .embeddingModel(mockModel)
                .build();
        assertThat(built).isNotNull();
    }
}
