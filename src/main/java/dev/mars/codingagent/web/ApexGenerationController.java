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
        // Input validation
        if (request.requirements() == null || request.requirements().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "requirements is required and must not be blank"));
        }
        if (request.dataStructure() == null || request.dataStructure().isBlank()) {
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

        // Evict expired jobs to prevent unbounded growth
        evictExpiredJobs();

        jobs.put(jobId, new JobEntry("running", null, null, Instant.now()));

        // Run generation asynchronously
        executor.submit(() -> {
            try {
                GenerationResult result = generationService.generate(genRequest);
                jobs.put(jobId, new JobEntry("completed", result, null, Instant.now()));
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
        List<Map<String, String>> jobList = new ArrayList<>();
        jobs.forEach((id, job) -> jobList.add(Map.of(
                "jobId", id,
                "status", job.status()
        )));
        return ResponseEntity.ok(jobList);
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
        Instant cutoff = Instant.now().minusMillis(JOB_TTL_MILLIS);
        jobs.entrySet().removeIf(e ->
                e.getValue().createdAt().isBefore(cutoff)
                        || (jobs.size() > MAX_JOBS && !"running".equals(e.getValue().status())));
    }
}
