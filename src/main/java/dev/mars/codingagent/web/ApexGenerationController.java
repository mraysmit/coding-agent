package dev.mars.codingagent.web;

import dev.mars.codingagent.orchestration.ApexGenerationService;
import dev.mars.codingagent.orchestration.GenerationRequest;
import dev.mars.codingagent.orchestration.GenerationResult;
import dev.mars.codingagent.orchestration.GenerationResult.GeneratedFile;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * REST controller for the APEX Rules Generation web UI.
 * Provides endpoints to submit generation requests and poll for results.
 */
@RestController
@RequestMapping("/api/apex")
public class ApexGenerationController {

    private static final Logger log = LoggerFactory.getLogger(ApexGenerationController.class);
    private static final int MAX_JOBS = 100;
    private static final long JOB_TTL_MILLIS = TimeUnit.HOURS.toMillis(1);

    private final ApexGenerationService generationService;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final ConcurrentHashMap<String, JobEntry> jobs = new ConcurrentHashMap<>();

    public ApexGenerationController(ApexGenerationService generationService) {
        this.generationService = generationService;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Submit a generation request. Returns immediately with a job ID.
     * Poll /api/apex/status/{jobId} for results.
     */
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generate(@RequestBody GenerateRequest request) {
        log.debug("POST /api/apex/generate — requirements='{}', dataStructure length={}, hints='{}'",
                request.requirements() != null ? request.requirements().substring(0, Math.min(100, request.requirements().length())) : "null",
                request.dataStructure() != null ? request.dataStructure().length() : 0,
                request.hints());

        // Input validation
        if (request.requirements() == null || request.requirements().isBlank()) {
            log.debug("Rejecting request: requirements is blank");
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "requirements is required and must not be blank"));
        }
        if (request.dataStructure() == null || request.dataStructure().isBlank()) {
            log.debug("Rejecting request: dataStructure is blank");
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "dataStructure is required and must not be blank"));
        }

        log.info("Received generation request: {}",
                request.requirements().substring(0, Math.min(80, request.requirements().length())));

        List<String> hints = request.hints() != null
                ? Arrays.stream(request.hints().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()
                : List.of();

        GenerationRequest genRequest = GenerationRequest.of(
                request.requirements(), request.dataStructure(), hints);

        String jobId = genRequest.requestId();
        log.debug("Created job {} with {} hints", jobId, hints.size());

        // Evict expired jobs to prevent unbounded growth
        evictExpiredJobs();

        jobs.put(jobId, new JobEntry("running", null, null, Instant.now()));

        // Run generation asynchronously
        executor.submit(() -> {
            try {
                log.debug("Starting async generation for job {}", jobId);
                GenerationResult result = generationService.generate(genRequest);
                jobs.put(jobId, new JobEntry("completed", result, null, Instant.now()));
                log.debug("Job {} completed. Success={}, files={}",
                        jobId, result.success(), result.files().size());
            } catch (Exception e) {
                log.error("Generation failed for job {}: {}", jobId, e.getMessage(), e);
                jobs.put(jobId, new JobEntry("failed", null, e.getMessage(), Instant.now()));
            }
        });

        return ResponseEntity.ok(Map.of(
                "jobId", jobId,
                "status", "running",
                "message", "Generation started. Poll /api/apex/status/" + jobId + " for results."
        ));
    }

    /**
     * Poll for job status and results.
     */
    @GetMapping("/status/{jobId}")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String jobId) {
        JobEntry job = jobs.get(jobId);
        log.debug("GET /api/apex/status/{} — found={}, status={}",
                jobId, job != null, job != null ? job.status() : "N/A");
        if (job == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobId", jobId);
        response.put("status", job.status());

        if ("completed".equals(job.status()) && job.result() != null) {
            GenerationResult result = job.result();
            response.put("success", result.success());
            response.put("attempts", result.attempts());
            response.put("summary", result.summary());

            List<Map<String, String>> files = new ArrayList<>();
            for (GeneratedFile f : result.files()) {
                Map<String, String> fileInfo = new LinkedHashMap<>();
                fileInfo.put("fileName", f.fileName());
                fileInfo.put("docType", f.docType());
                fileInfo.put("content", f.content());
                files.add(fileInfo);
            }
            response.put("files", files);

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("lexicalValid", result.validationReport().lexicalValid());
            report.put("compilationSuccess", result.validationReport().compilationSuccess());
            report.put("executionSuccess", result.validationReport().executionSuccess());
            report.put("expectationsPass", result.validationReport().expectationsPass());
            report.put("totalErrors", result.validationReport().totalErrors());
            report.put("totalWarnings", result.validationReport().totalWarnings());

            // Include per-stage detail extracted from the raw validation report
            Map<String, Object> executionDetails = result.validationReport().executionDetails();
            String rawReport = executionDetails != null
                    ? (String) executionDetails.getOrDefault("rawReport", "") : "";
            report.put("stageDetails", buildStageDetails(rawReport, result.validationReport()));

            // Include captured rule execution results if available
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> ruleResults = executionDetails != null
                    ? (List<Map<String, Object>>) executionDetails.getOrDefault("ruleResults", List.of())
                    : List.of();
            if (!ruleResults.isEmpty()) {
                report.put("ruleResults", ruleResults);
            }

            // Include structured issues if any
            List<Map<String, String>> issueList = new ArrayList<>();
            for (GenerationResult.ValidationIssue issue : result.validationReport().issues()) {
                Map<String, String> issueMap = new LinkedHashMap<>();
                issueMap.put("errorCode", issue.errorCode());
                issueMap.put("message", issue.message());
                issueMap.put("stage", issue.stage());
                issueMap.put("severity", issue.severity());
                issueList.add(issueMap);
            }
            report.put("issues", issueList);

            response.put("validationReport", report);

            if (result.outputDirectory() != null) {
                response.put("outputDirectory", result.outputDirectory().toString());
            }
        } else if ("failed".equals(job.status())) {
            response.put("error", job.error());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * List all jobs.
     */
    @GetMapping("/jobs")
    public ResponseEntity<List<Map<String, String>>> listJobs() {
        log.debug("GET /api/apex/jobs — total jobs: {}", jobs.size());
        List<Map<String, String>> jobList = new ArrayList<>();
        jobs.forEach((id, job) -> jobList.add(Map.of(
                "jobId", id,
                "status", job.status()
        )));
        return ResponseEntity.ok(jobList);
    }

    // ---- Validation stage detail extraction ----

    /**
     * Extracts per-stage detail text from the validation report.
     * Uses structured per-stage blocks extracted by OutputPackager when available,
     * falls back to extracting from the raw report text.
     */
    private Map<String, Object> buildStageDetails(String rawReport,
                                                   GenerationResult.ValidationReport report) {
        Map<String, Object> details = new LinkedHashMap<>();
        Map<String, Object> execDetails = report.executionDetails();

        String[] stages = {"lexical", "compilation", "execution", "expectation"};
        String[] detailKeys = {"lexicalDetail", "compilationDetail", "executionDetail", "expectationDetail"};
        boolean[] results = {
                report.lexicalValid(), report.compilationSuccess(),
                report.executionSuccess(), report.expectationsPass()
        };
        String[] labels = {"Lexical Validation", "Compilation", "Execution", "Expectations"};

        for (int i = 0; i < stages.length; i++) {
            Map<String, Object> stage = new LinkedHashMap<>();
            stage.put("pass", results[i]);
            stage.put("label", labels[i]);

            // Try structured per-stage detail first (from OutputPackager extraction)
            String stageDetail = execDetails != null
                    ? (String) execDetails.getOrDefault(detailKeys[i], "") : "";

            // Fall back to raw report text extraction
            if (stageDetail == null || stageDetail.isBlank()) {
                stageDetail = extractStageText(rawReport, stages[i],
                        i + 1 < stages.length ? stages[i + 1] : null);
            }

            if (stageDetail != null && !stageDetail.isBlank()) {
                stage.put("detail", stageDetail.trim());
            } else {
                stage.put("detail", labels[i] + ": " + (results[i] ? "PASS" : "FAIL"));
            }

            details.put(stages[i], stage);
        }

        // Add overall summary info
        details.put("totalErrors", report.totalErrors());
        details.put("totalWarnings", report.totalWarnings());
        if (rawReport != null && !rawReport.isBlank()) {
            details.put("rawReport", rawReport);
        }

        return details;
    }

    /**
     * Extracts text from the raw report between one stage keyword and the next.
     */
    private String extractStageText(String rawReport, String stage, String nextStage) {
        if (rawReport == null || rawReport.isBlank()) return null;

        String lower = rawReport.toLowerCase();
        int start = lower.indexOf(stage);
        if (start < 0) return null;

        // Find the beginning of the line containing this stage
        int lineStart = rawReport.lastIndexOf('\n', start);
        lineStart = lineStart < 0 ? 0 : lineStart + 1;

        // Find the end: either the next stage keyword or end of text
        int end = rawReport.length();
        if (nextStage != null) {
            int nextIdx = lower.indexOf(nextStage, start + stage.length());
            if (nextIdx > 0) {
                // Back up to the beginning of that line
                int nextLineStart = rawReport.lastIndexOf('\n', nextIdx);
                if (nextLineStart > lineStart) {
                    end = nextLineStart;
                }
            }
        }

        return rawReport.substring(lineStart, end).trim();
    }

    // ---- DTOs ----

    public record GenerateRequest(
            String requirements,
            String dataStructure,
            String hints
    ) {}

    private record JobEntry(
            String status,
            GenerationResult result,
            String error,
            Instant createdAt
    ) {}

    private void evictExpiredJobs() {
        int before = jobs.size();
        Instant cutoff = Instant.now().minusMillis(JOB_TTL_MILLIS);
        jobs.entrySet().removeIf(e ->
                e.getValue().createdAt().isBefore(cutoff)
                        || (jobs.size() > MAX_JOBS && !"running".equals(e.getValue().status())));
        int evicted = before - jobs.size();
        if (evicted > 0) {
            log.debug("Evicted {} expired jobs ({} remaining)", evicted, jobs.size());
        }
    }
}
