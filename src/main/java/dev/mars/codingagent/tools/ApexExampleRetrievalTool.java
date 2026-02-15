package dev.mars.codingagent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Typed tool for retrieving relevant APEX YAML examples from the indexed corpus.
 * Uses tag-based filtering on the example index to find feature-matched examples.
 * The agent can then read the matched files using FileSystemTools.
 */
public class ApexExampleRetrievalTool {

    private final ObjectMapper objectMapper;
    private final List<Map<String, Object>> fileEntries;
    private final Path apexProjectRoot;

    @SuppressWarnings("unchecked")
    private ApexExampleRetrievalTool(ObjectMapper objectMapper, Path indexPath, Path apexProjectRoot) throws IOException {
        this.objectMapper = objectMapper;
        this.apexProjectRoot = apexProjectRoot;

        JsonNode root = objectMapper.readTree(Files.readAllBytes(indexPath));
        JsonNode filesNode = root.get("files");
        if (filesNode != null && filesNode.isArray()) {
            this.fileEntries = objectMapper.convertValue(filesNode,
                    new TypeReference<List<Map<String, Object>>>() {});
        } else {
            this.fileEntries = List.of();
        }
    }

    /**
     * Searches the example index for YAML files matching the given criteria.
     * Ranks by feature overlap count and returns top-K results.
     */
    @Tool(name = "ApexSearchExamples", description = """
            Searches the APEX example corpus for YAML files matching the given criteria.
            Filter by document type, required features, and/or complexity.
            Returns file paths (relative to apex-rules-engine), document type, features,
            complexity, and SpEL patterns found.
            
            Feature tags include: rules, enrichments, rule-groups, enrichment-groups,
            transformations, scenario, pipeline, data-sources, error-recovery, etc.
            
            Complexity levels: simple, moderate, complex.
            
            After getting results, read the actual YAML files to use as few-shot examples.
            """)
    @SuppressWarnings("unchecked")
    public String searchExamples(
            @ToolParam(description = "Comma-separated feature tags to match (e.g., 'rules,enrichments,rule-groups')") String featureTags,
            @ToolParam(description = "Document type filter (e.g., 'rule-config', 'scenario'). Use 'any' for no filter.", required = false) String docType,
            @ToolParam(description = "Maximum number of results to return (default: 5)", required = false) Integer topK) {

        if (topK == null || topK <= 0) topK = 5;

        Set<String> requestedFeatures = Arrays.stream(featureTags.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());

        String docTypeFilter = (docType != null && !"any".equalsIgnoreCase(docType.trim()))
                ? docType.trim().toLowerCase() : null;

        // Score and filter
        List<ScoredEntry> scored = new ArrayList<>();
        for (Map<String, Object> entry : fileEntries) {
            // Document type filter
            if (docTypeFilter != null) {
                String entryType = entry.containsKey("metadataType")
                        ? entry.get("metadataType").toString().toLowerCase() : "";
                if (!entryType.equals(docTypeFilter) && !entryType.equals("none")) {
                    continue;
                }
            }

            // Score by feature overlap
            List<String> entryFeatures = entry.containsKey("features")
                    ? (List<String>) entry.get("features") : List.of();
            Set<String> featureSet = new HashSet<>(entryFeatures);
            long overlap = requestedFeatures.stream()
                    .filter(featureSet::contains)
                    .count();

            if (overlap > 0 || requestedFeatures.isEmpty()) {
                scored.add(new ScoredEntry(entry, overlap, featureSet.size()));
            }
        }

        // Sort by overlap descending, then by total features (prefer simpler examples on ties)
        scored.sort((a, b) -> {
            int cmp = Long.compare(b.overlapScore, a.overlapScore);
            if (cmp != 0) return cmp;
            return Integer.compare(a.totalFeatures, b.totalFeatures);
        });

        // Take top-K
        List<Map<String, Object>> results = new ArrayList<>();
        int limit = Math.min(topK, scored.size());
        for (int i = 0; i < limit; i++) {
            ScoredEntry se = scored.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", se.entry.get("path"));
            item.put("absolutePath", apexProjectRoot.resolve(se.entry.get("path").toString()).toString());
            item.put("metadataType", se.entry.get("metadataType"));
            item.put("features", se.entry.get("features"));
            item.put("spelPatterns", se.entry.get("spelPatterns"));
            item.put("complexity", se.entry.get("complexity"));
            item.put("lineCount", se.entry.get("lineCount"));
            item.put("matchScore", se.overlapScore);
            results.add(item);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", Map.of(
                "featureTags", requestedFeatures,
                "docType", docTypeFilter != null ? docTypeFilter : "any",
                "topK", topK
        ));
        response.put("totalMatches", scored.size());
        response.put("returned", results.size());
        response.put("results", results);

        if (results.isEmpty()) {
            response.put("suggestion", "Try broader feature tags or use 'any' for docType");
        }

        return toJson(response);
    }

    /**
     * Returns corpus statistics: feature counts, complexity distribution, etc.
     */
    @Tool(name = "ApexCorpusStats", description = """
            Returns aggregate statistics about the APEX example corpus.
            Includes total file count, feature distribution, complexity breakdown,
            and available document types.
            Use this to understand what examples are available before searching.
            """)
    @SuppressWarnings("unchecked")
    public String getCorpusStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalFiles", fileEntries.size());

        // Count by complexity
        Map<String, Integer> complexity = new LinkedHashMap<>();
        // Count by features
        Map<String, Integer> features = new LinkedHashMap<>();
        // Count by docType
        Map<String, Integer> docTypes = new LinkedHashMap<>();

        for (Map<String, Object> entry : fileEntries) {
            // Complexity
            String c = entry.containsKey("complexity") ? entry.get("complexity").toString() : "unknown";
            complexity.merge(c, 1, Integer::sum);

            // Features
            List<String> feats = entry.containsKey("features")
                    ? (List<String>) entry.get("features") : List.of();
            for (String f : feats) {
                features.merge(f, 1, Integer::sum);
            }

            // DocType
            String dt = entry.containsKey("metadataType") ? entry.get("metadataType").toString() : "NONE";
            docTypes.merge(dt, 1, Integer::sum);
        }

        stats.put("complexityDistribution", complexity);
        stats.put("featureCounts", sortByValueDesc(features));
        stats.put("documentTypeCounts", sortByValueDesc(docTypes));

        return toJson(stats);
    }

    // ---- Helpers ----

    private record ScoredEntry(Map<String, Object> entry, long overlapScore, int totalFeatures) {}

    private Map<String, Integer> sortByValueDesc(Map<String, Integer> map) {
        return map.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{\"error\": \"Failed to serialize result: " + e.getMessage() + "\"}";
        }
    }

    // ---- Builder ----

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private ObjectMapper objectMapper = new ObjectMapper();
        private Path indexPath = Path.of("knowledge", "example-index.json");
        private Path apexProjectRoot = Path.of("..", "apex-rules-engine");

        private Builder() {}

        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        public Builder indexPath(Path path) {
            this.indexPath = path;
            return this;
        }

        public Builder apexProjectRoot(Path path) {
            this.apexProjectRoot = path;
            return this;
        }

        public ApexExampleRetrievalTool build() {
            try {
                return new ApexExampleRetrievalTool(objectMapper, indexPath, apexProjectRoot);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load example index from " + indexPath, e);
            }
        }
    }
}
