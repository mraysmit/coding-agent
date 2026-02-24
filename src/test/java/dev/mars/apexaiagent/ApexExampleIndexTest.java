package dev.mars.apexaiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the example index at knowledge/example-index.json.
 * Ensures structural integrity, minimum coverage, and consistency.
 */
class ApexExampleIndexTest {

    private static JsonNode root;

    @BeforeAll
    static void loadIndex() throws Exception {
        Path indexPath = Path.of("knowledge", "example-index.json");
        assertTrue(Files.exists(indexPath),
                "Example index must exist at knowledge/example-index.json");

        ObjectMapper mapper = new ObjectMapper();
        root = mapper.readTree(Files.readAllBytes(indexPath));
        assertNotNull(root, "Index should parse as valid JSON");
    }

    @Test
    void hasRequiredTopLevelFields() {
        assertTrue(root.has("generated"), "Must have 'generated' field");
        assertTrue(root.has("totalFiles"), "Must have 'totalFiles' field");
        assertTrue(root.has("directories"), "Must have 'directories' field");
        assertTrue(root.has("statistics"), "Must have 'statistics' field");
        assertTrue(root.has("files"), "Must have 'files' array");
    }

    @Test
    void totalFilesIsReasonable() {
        int total = root.get("totalFiles").asInt();
        assertTrue(total >= 100,
                "Should index at least 100 files, got " + total);
    }

    @Test
    void filesArrayMatchesTotalFiles() {
        int total = root.get("totalFiles").asInt();
        int arraySize = root.get("files").size();
        assertEquals(total, arraySize,
                "files array size should match totalFiles");
    }

    @Test
    void eachFileHasRequiredFields() {
        JsonNode files = root.get("files");
        int checked = 0;
        for (JsonNode file : files) {
            assertTrue(file.has("path"),
                    "File entry missing 'path': " + file);
            assertTrue(file.has("directory"),
                    "File entry missing 'directory': " + file.get("path"));
            assertTrue(file.has("complexity"),
                    "File entry missing 'complexity': " + file.get("path"));
            assertTrue(file.has("features"),
                    "File entry missing 'features': " + file.get("path"));
            checked++;
        }
        assertTrue(checked > 0, "Should have checked at least one file");
    }

    @Test
    void complexityValuesAreValid() {
        Set<String> validValues = Set.of("simple", "moderate", "complex");
        JsonNode files = root.get("files");
        for (JsonNode file : files) {
            String complexity = file.get("complexity").asText();
            assertTrue(validValues.contains(complexity),
                    "Invalid complexity '" + complexity + "' in " + file.get("path"));
        }
    }

    @Test
    void statisticsHasFeatureAndComplexityCounts() {
        JsonNode stats = root.get("statistics");
        assertTrue(stats.has("featureCounts"), "Stats must have featureCounts");
        assertTrue(stats.has("complexityDistribution"), "Stats must have complexityDistribution");
    }

    @Test
    void complexityDistributionSumsToTotal() {
        JsonNode dist = root.get("statistics").get("complexityDistribution");
        int sum = 0;
        var fieldNames = dist.fieldNames();
        while (fieldNames.hasNext()) {
            sum += dist.get(fieldNames.next()).asInt();
        }
        int total = root.get("totalFiles").asInt();
        assertEquals(total, sum,
                "Complexity distribution should sum to totalFiles");
    }

    @Test
    void directoriesCoverExpectedPaths() {
        JsonNode dirs = root.get("directories");
        // We expect at least these directories
        boolean hasPlayground = false;
        boolean hasDemo = false;
        boolean hasCore = false;
        var fieldNames = dirs.fieldNames();
        while (fieldNames.hasNext()) {
            String dir = fieldNames.next();
            if (dir.contains("playground")) hasPlayground = true;
            if (dir.contains("demo")) hasDemo = true;
            if (dir.contains("core")) hasCore = true;
        }
        assertTrue(hasPlayground, "Should index apex-playground examples");
        assertTrue(hasDemo, "Should index apex-demo test resources");
        assertTrue(hasCore, "Should index apex-core test resources");
    }

    @Test
    void pathsAreUnique() {
        JsonNode files = root.get("files");
        Set<String> paths = new HashSet<>();
        for (JsonNode file : files) {
            String path = file.get("path").asText();
            assertTrue(paths.add(path),
                    "Duplicate path found: " + path);
        }
    }

    @Test
    void featureCountsHaveRulesAndEnrichments() {
        JsonNode features = root.get("statistics").get("featureCounts");
        assertTrue(features.has("rules"),
                "Feature counts should include 'rules'");
        assertTrue(features.has("enrichments"),
                "Feature counts should include 'enrichments'");
        assertTrue(features.get("rules").asInt() > 50,
                "Should have > 50 files with rules");
        assertTrue(features.get("enrichments").asInt() > 50,
                "Should have > 50 files with enrichments");
    }
}
