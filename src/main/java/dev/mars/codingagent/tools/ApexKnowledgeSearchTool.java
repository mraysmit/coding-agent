package dev.mars.codingagent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

/**
 * Semantic search tool that queries the APEX knowledge vector store.
 * <p>
 * Unlike the tag-based {@code ApexExampleRetrievalTool}, this tool performs
 * natural-language similarity search across all embedded documentation and
 * YAML examples, making it ideal for conceptual queries like
 * "how to use enrichment groups with field mappings" or
 * "error recovery patterns for pipelines".
 */
public class ApexKnowledgeSearchTool {

    private static final Logger log = LoggerFactory.getLogger(ApexKnowledgeSearchTool.class);

    private final VectorStore vectorStore;

    private ApexKnowledgeSearchTool(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Performs semantic search across all APEX documentation and YAML examples.
     */
    @Tool(name = "ApexSemanticSearch", description = """
            Semantic search across APEX YAML documentation, guides, and hundreds of YAML examples.
            Use this when you need to understand APEX concepts, find usage patterns, or discover
            how specific features work together. This searches by meaning, not just keywords.
            
            Good queries:
            - "how to define enrichment groups with field mappings"
            - "SpEL expressions for date comparisons"
            - "error recovery and fallback patterns"
            - "rule-group execution order and priority"
            - "scenario with multiple test cases for validation rules"
            
            Returns matching document chunks with source file paths and relevance metadata.
            For exact feature tag matching, use ApexSearchExamples instead.
            """)
    public String semanticSearch(
            @ToolParam(description = "Natural language query describing what you're looking for") String query,
            @ToolParam(description = "Maximum number of results to return (default: 5)", required = false) Integer topK) {

        if (topK == null || topK <= 0) topK = 5;
        log.debug("[ApexSemanticSearch] query='{}', topK={}", query, topK);

        try {
            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(0.5)
                    .build();

            List<Document> results = vectorStore.similaritySearch(request);

            if (results == null || results.isEmpty()) {
                log.debug("[ApexSemanticSearch] No results for query '{}'", query);
                return "No matching documents found for: " + query;
            }

            log.debug("[ApexSemanticSearch] Found {} results", results.size());

            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(results.size()).append(" relevant results for: \"").append(query).append("\"\n\n");

            for (int i = 0; i < results.size(); i++) {
                Document doc = results.get(i);
                sb.append("--- Result ").append(i + 1).append(" ---\n");

                // Metadata
                var meta = doc.getMetadata();
                if (meta != null) {
                    if (meta.containsKey("source")) sb.append("Source: ").append(meta.get("source")).append("\n");
                    if (meta.containsKey("contentType")) sb.append("Type: ").append(meta.get("contentType")).append("\n");
                    if (meta.containsKey("section")) sb.append("Section: ").append(meta.get("section")).append("\n");
                    if (meta.containsKey("docType")) sb.append("DocType: ").append(meta.get("docType")).append("\n");
                    if (meta.containsKey("category")) sb.append("Category: ").append(meta.get("category")).append("\n");
                    if (meta.containsKey("features")) sb.append("Features: ").append(meta.get("features")).append("\n");
                }

                // Content (truncated for readability)
                String content = doc.getText();
                if (content != null) {
                    if (content.length() > 2000) {
                        sb.append("Content (truncated):\n").append(content, 0, 2000).append("\n... (truncated)\n");
                    } else {
                        sb.append("Content:\n").append(content).append("\n");
                    }
                }
                sb.append("\n");
            }

            return sb.toString();
        } catch (Exception e) {
            log.error("[ApexSemanticSearch] Error during search: {}", e.getMessage(), e);
            return "Error performing semantic search: " + e.getMessage();
        }
    }

    // ---- Builder ----

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private VectorStore vectorStore;

        private Builder() {}

        public Builder vectorStore(VectorStore vectorStore) {
            this.vectorStore = vectorStore;
            return this;
        }

        public ApexKnowledgeSearchTool build() {
            if (vectorStore == null) {
                throw new IllegalStateException("vectorStore is required");
            }
            return new ApexKnowledgeSearchTool(vectorStore);
        }
    }
}
