package dev.mars.codingagent.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ingests APEX documentation and YAML examples into a vector store for RAG.
 * <p>
 * Processes two types of content:
 * <ul>
 *   <li><b>Markdown docs</b> — chunked by heading (## / ###) sections</li>
 *   <li><b>YAML examples</b> — each file as a single document with extracted metadata</li>
 * </ul>
 * <p>
 * The vector store is persisted to disk ({@code knowledge/apex-vector-store.json})
 * and reloaded on subsequent startups to avoid re-embedding.
 */
public class ApexKnowledgeIngester {

    private static final Logger log = LoggerFactory.getLogger(ApexKnowledgeIngester.class);

    private static final int MAX_CHUNK_CHARS = 4000;
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,4})\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern YAML_METADATA_ID = Pattern.compile("^\\s{0,4}id:\\s*[\"']?(.+?)[\"']?\\s*$", Pattern.MULTILINE);
    private static final Pattern YAML_METADATA_NAME = Pattern.compile("^\\s{0,4}name:\\s*[\"']?(.+?)[\"']?\\s*$", Pattern.MULTILINE);
    private static final Pattern YAML_METADATA_TYPE = Pattern.compile("^\\s{0,4}type:\\s*[\"']?(.+?)[\"']?\\s*$", Pattern.MULTILINE);
    private static final Pattern YAML_METADATA_DESC = Pattern.compile("^\\s{0,4}description:\\s*[\"']?(.+?)[\"']?\\s*$", Pattern.MULTILINE);
    private static final Pattern YAML_PURPOSE_COMMENT = Pattern.compile("^#\\s*Purpose:\\s*(.+)$", Pattern.MULTILINE);

    private final Path apexProjectRoot;
    private final Path vectorStorePath;
    private final EmbeddingModel embeddingModel;

    public ApexKnowledgeIngester(Path apexProjectRoot, Path vectorStorePath, EmbeddingModel embeddingModel) {
        this.apexProjectRoot = apexProjectRoot;
        this.vectorStorePath = vectorStorePath;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Loads the vector store from disk if it exists, otherwise builds it from scratch.
     *
     * @return the populated vector store
     */
    public SimpleVectorStore loadOrBuild() {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();

        if (Files.exists(vectorStorePath)) {
            log.info("Loading existing vector store from {}", vectorStorePath);
            store.load(vectorStorePath.toFile());
            return store;
        }

        log.info("Vector store not found at {}. Building from APEX corpus...", vectorStorePath);
        return buildVectorStore(store);
    }

    /**
     * Forces a full rebuild of the vector store from the APEX corpus.
     *
     * @return the rebuilt vector store
     */
    public SimpleVectorStore rebuild() {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        return buildVectorStore(store);
    }

    private SimpleVectorStore buildVectorStore(SimpleVectorStore store) {
        List<Document> allDocuments = new ArrayList<>();

        // 1. Ingest markdown documentation
        List<Path> docPaths = List.of(
                apexProjectRoot.resolve("docs"),
                apexProjectRoot.resolve("apex-compiler"),
                apexProjectRoot.resolve("apex-playground/docs")
        );
        for (Path docDir : docPaths) {
            if (Files.isDirectory(docDir)) {
                allDocuments.addAll(ingestMarkdownDirectory(docDir));
            }
        }
        // Also ingest root README
        Path rootReadme = apexProjectRoot.resolve("README.md");
        if (Files.isRegularFile(rootReadme)) {
            allDocuments.addAll(chunkMarkdownFile(rootReadme));
        }

        log.info("Ingested {} document chunks from markdown files", allDocuments.size());

        // 2. Ingest YAML examples
        List<Path> yamlDirs = List.of(
                apexProjectRoot.resolve("apex-playground/examples"),
                apexProjectRoot.resolve("apex-demo/src/test/resources"),
                apexProjectRoot.resolve("apex-core/src/test/resources")
        );
        int yamlCount = 0;
        for (Path yamlDir : yamlDirs) {
            if (Files.isDirectory(yamlDir)) {
                List<Document> yamlDocs = ingestYamlDirectory(yamlDir);
                allDocuments.addAll(yamlDocs);
                yamlCount += yamlDocs.size();
            }
        }
        log.info("Ingested {} YAML example documents", yamlCount);

        log.info("Total documents to embed: {}", allDocuments.size());

        // Batch add to avoid memory issues with large numbers
        int batchSize = 50;
        for (int i = 0; i < allDocuments.size(); i += batchSize) {
            int end = Math.min(i + batchSize, allDocuments.size());
            List<Document> batch = allDocuments.subList(i, end);
            store.doAdd(batch);
            log.debug("Embedded batch {}-{} of {} documents", i, end, allDocuments.size());
        }

        // Persist to disk
        try {
            Files.createDirectories(vectorStorePath.getParent());
            store.save(vectorStorePath.toFile());
            log.info("Vector store saved to {} ({} documents)", vectorStorePath, allDocuments.size());
        } catch (Exception e) {
            log.error("Failed to save vector store: {}", e.getMessage(), e);
        }

        return store;
    }

    // ---- Markdown Processing ----

    List<Document> ingestMarkdownDirectory(Path dir) {
        List<Document> docs = new ArrayList<>();
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().toLowerCase().endsWith(".md")) {
                        docs.addAll(chunkMarkdownFile(file));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Error scanning markdown directory {}: {}", dir, e.getMessage());
        }
        return docs;
    }

    /**
     * Chunks a markdown file by heading boundaries.
     * Each chunk includes the heading hierarchy for context.
     */
    List<Document> chunkMarkdownFile(Path file) {
        List<Document> chunks = new ArrayList<>();
        try {
            String content = Files.readString(file);
            String relativePath = apexProjectRoot.relativize(file).toString().replace('\\', '/');

            // Split by ## or ### headings
            List<Section> sections = splitByHeadings(content);

            if (sections.isEmpty()) {
                // No headings found — treat entire file as one chunk
                if (content.length() <= MAX_CHUNK_CHARS) {
                    chunks.add(createDocumentChunk(content, relativePath, file.getFileName().toString(), "full-file"));
                } else {
                    // Split into fixed-size chunks
                    chunks.addAll(splitLargeText(content, relativePath, file.getFileName().toString()));
                }
            } else {
                for (Section section : sections) {
                    String chunkText = section.heading + "\n\n" + section.body;
                    if (chunkText.length() <= MAX_CHUNK_CHARS) {
                        chunks.add(createDocumentChunk(chunkText, relativePath,
                                file.getFileName().toString(), section.heading));
                    } else {
                        chunks.addAll(splitLargeText(chunkText, relativePath, section.heading));
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read markdown file {}: {}", file, e.getMessage());
        }
        return chunks;
    }

    private Document createDocumentChunk(String text, String filePath, String fileName, String section) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", filePath);
        metadata.put("fileName", fileName);
        metadata.put("section", section);
        metadata.put("contentType", "documentation");
        return new Document(text, metadata);
    }

    private List<Document> splitLargeText(String text, String filePath, String sectionName) {
        List<Document> chunks = new ArrayList<>();
        int partNum = 0;
        for (int i = 0; i < text.length(); i += MAX_CHUNK_CHARS) {
            partNum++;
            int end = Math.min(i + MAX_CHUNK_CHARS, text.length());
            // Try to break at a paragraph boundary
            if (end < text.length()) {
                int paraBreak = text.lastIndexOf("\n\n", end);
                if (paraBreak > i + MAX_CHUNK_CHARS / 2) {
                    end = paraBreak;
                }
            }
            String chunk = text.substring(i, end);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", filePath);
            metadata.put("section", sectionName + " (part " + partNum + ")");
            metadata.put("contentType", "documentation");
            chunks.add(new Document(chunk, metadata));
        }
        return chunks;
    }

    // ---- YAML Processing ----

    List<Document> ingestYamlDirectory(Path dir) {
        List<Document> docs = new ArrayList<>();
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.toString().toLowerCase();
                    if (name.endsWith(".yaml") || name.endsWith(".yml")) {
                        Document doc = processYamlFile(file);
                        if (doc != null) docs.add(doc);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Error scanning YAML directory {}: {}", dir, e.getMessage());
        }
        return docs;
    }

    /**
     * Processes a single YAML file into a Document.
     * Extracts metadata from the YAML content and header comments.
     * Constructs a searchable text that includes both a natural-language summary
     * and the YAML content itself.
     */
    Document processYamlFile(Path file) {
        try {
            String content = Files.readString(file);
            if (content.isBlank()) return null;

            String relativePath = apexProjectRoot.relativize(file).toString().replace('\\', '/');

            // Extract YAML metadata fields
            String yamlId = extractPattern(content, YAML_METADATA_ID);
            String yamlName = extractPattern(content, YAML_METADATA_NAME);
            String yamlType = extractPattern(content, YAML_METADATA_TYPE);
            String yamlDesc = extractPattern(content, YAML_METADATA_DESC);
            String purpose = extractPattern(content, YAML_PURPOSE_COMMENT);

            // Detect features present in the file
            List<String> features = detectFeatures(content);

            // Determine the parent directory category
            String category = file.getParent() != null
                    ? file.getParent().getFileName().toString() : "unknown";

            // Build a searchable summary prefix
            StringBuilder summary = new StringBuilder();
            summary.append("APEX YAML Example: ");
            if (yamlName != null) summary.append(yamlName);
            else summary.append(file.getFileName().toString());
            summary.append("\n");

            if (yamlType != null) summary.append("Type: ").append(yamlType).append("\n");
            if (yamlDesc != null) summary.append("Description: ").append(yamlDesc).append("\n");
            if (purpose != null) summary.append("Purpose: ").append(purpose).append("\n");
            if (!features.isEmpty()) summary.append("Features: ").append(String.join(", ", features)).append("\n");
            summary.append("Category: ").append(category).append("\n");
            summary.append("File: ").append(relativePath).append("\n\n");

            // Truncate very large YAML files to fit in the chunk limit
            String yamlContent = content.length() > MAX_CHUNK_CHARS - summary.length()
                    ? content.substring(0, MAX_CHUNK_CHARS - summary.length()) + "\n# ... (truncated)"
                    : content;

            String documentText = summary + yamlContent;

            // Build metadata
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", relativePath);
            metadata.put("fileName", file.getFileName().toString());
            metadata.put("contentType", "yaml-example");
            metadata.put("category", category);
            if (yamlId != null) metadata.put("yamlId", yamlId);
            if (yamlType != null) metadata.put("docType", yamlType);
            if (!features.isEmpty()) metadata.put("features", String.join(",", features));

            return new Document(documentText, metadata);
        } catch (IOException e) {
            log.warn("Failed to read YAML file {}: {}", file, e.getMessage());
            return null;
        }
    }

    // ---- Helpers ----

    private String extractPattern(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    List<String> detectFeatures(String yamlContent) {
        List<String> features = new ArrayList<>();
        String lower = yamlContent.toLowerCase();
        if (lower.contains("rules:")) features.add("rules");
        if (lower.contains("rule-groups:")) features.add("rule-groups");
        if (lower.contains("enrichments:")) features.add("enrichments");
        if (lower.contains("enrichment-groups:")) features.add("enrichment-groups");
        if (lower.contains("data-sources:") || lower.contains("data-source-refs:")) features.add("data-sources");
        if (lower.contains("scenario:") || lower.contains("scenarios:")) features.add("scenario");
        if (lower.contains("pipeline:")) features.add("pipeline");
        if (lower.contains("components:") || lower.contains("component:")) features.add("components");
        if (lower.contains("lookup")) features.add("lookup");
        if (lower.contains("transformation")) features.add("transformations");
        if (lower.contains("error-recovery") || lower.contains("error-handler")) features.add("error-recovery");
        if (lower.contains("field-mappings:")) features.add("field-mappings");
        if (lower.contains("severity:")) features.add("severity");
        if (lower.contains("condition:")) features.add("conditions");
        return features;
    }

    record Section(String heading, String body) {}

    List<Section> splitByHeadings(String markdown) {
        List<Section> sections = new ArrayList<>();
        Matcher m = HEADING_PATTERN.matcher(markdown);

        int lastEnd = 0;
        String lastHeading = null;

        while (m.find()) {
            if (lastHeading != null) {
                String body = markdown.substring(lastEnd, m.start()).trim();
                if (!body.isEmpty()) {
                    sections.add(new Section(lastHeading, body));
                }
            }
            lastHeading = m.group(0).trim();
            lastEnd = m.end();
        }

        // Last section
        if (lastHeading != null) {
            String body = markdown.substring(lastEnd).trim();
            if (!body.isEmpty()) {
                sections.add(new Section(lastHeading, body));
            }
        }

        return sections;
    }

    // ---- Builder ----

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Path apexProjectRoot = Path.of("..", "apex-rules-engine");
        private Path vectorStorePath = Path.of("knowledge", "apex-vector-store.json");
        private EmbeddingModel embeddingModel;

        private Builder() {}

        public Builder apexProjectRoot(Path path) {
            this.apexProjectRoot = path;
            return this;
        }

        public Builder vectorStorePath(Path path) {
            this.vectorStorePath = path;
            return this;
        }

        public Builder embeddingModel(EmbeddingModel model) {
            this.embeddingModel = model;
            return this;
        }

        public ApexKnowledgeIngester build() {
            if (embeddingModel == null) {
                throw new IllegalStateException("embeddingModel is required");
            }
            return new ApexKnowledgeIngester(apexProjectRoot, vectorStorePath, embeddingModel);
        }
    }
}
