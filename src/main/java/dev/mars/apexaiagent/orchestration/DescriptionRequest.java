package dev.mars.apexaiagent.orchestration;

import java.time.Instant;
import java.util.UUID;

/**
 * Input to the APEX description pipeline.
 * Contains APEX YAML configuration and optional sample JSON data,
 * and produces a plain-language business description.
 */
public record DescriptionRequest(
        String requestId,
        String yamlContent,
        String sampleJson,
        String focusArea
) {
    /**
     * Create a request from YAML only (no sample data).
     */
    public static DescriptionRequest of(String yamlContent) {
        if (yamlContent == null || yamlContent.isBlank()) {
            throw new IllegalArgumentException("yamlContent must not be null or blank");
        }
        return new DescriptionRequest(
                "desc-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8),
                yamlContent,
                null,
                null
        );
    }

    /**
     * Create a request from YAML + sample JSON data.
     */
    public static DescriptionRequest of(String yamlContent, String sampleJson) {
        if (yamlContent == null || yamlContent.isBlank()) {
            throw new IllegalArgumentException("yamlContent must not be null or blank");
        }
        return new DescriptionRequest(
                "desc-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8),
                yamlContent,
                sampleJson != null && !sampleJson.isBlank() ? sampleJson : null,
                null
        );
    }

    /**
     * Create a request with a specific focus area (e.g. "explain the discount rules only").
     */
    public static DescriptionRequest of(String yamlContent, String sampleJson, String focusArea) {
        if (yamlContent == null || yamlContent.isBlank()) {
            throw new IllegalArgumentException("yamlContent must not be null or blank");
        }
        return new DescriptionRequest(
                "desc-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8),
                yamlContent,
                sampleJson != null && !sampleJson.isBlank() ? sampleJson : null,
                focusArea != null && !focusArea.isBlank() ? focusArea : null
        );
    }
}
