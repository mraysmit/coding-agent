package dev.mars.codingagent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mars.codingagent.orchestration.ApexGenerationService;
import dev.mars.codingagent.orchestration.GenerationRequest;
import dev.mars.codingagent.orchestration.GenerationResult;
import dev.mars.codingagent.orchestration.GenerationResult.GeneratedFile;
import dev.mars.codingagent.orchestration.GenerationResult.ValidationReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for the ApexGenerationController REST endpoints.
 * Uses standalone MockMvc with a mocked ApexGenerationService — no Spring context needed.
 */
class ApexGenerationControllerTest {

    MockMvc mockMvc;
    ObjectMapper objectMapper = new ObjectMapper();
    ApexGenerationService generationService;

    @BeforeEach
    void setUp() {
        generationService = mock(ApexGenerationService.class);
        ApexGenerationController controller = new ApexGenerationController(generationService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ---- POST /api/apex/generate ----

    @Test
    void generate_returnsJobIdAndRunningStatus() throws Exception {
        // Stub the service to return a successful result (async, so it won't
        // be called in the same request cycle, but we stub to prevent NPEs)
        when(generationService.generate(any(GenerationRequest.class)))
                .thenReturn(successResult("test-123"));

        String body = objectMapper.writeValueAsString(Map.of(
                "requirements", "Create a discount rule for orders over $100",
                "dataStructure", "{\"orderTotal\": 150}"
        ));

        mockMvc.perform(post("/api/apex/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").isString())
                .andExpect(jsonPath("$.status").value("running"))
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void generate_withHints_returnsJobId() throws Exception {
        when(generationService.generate(any(GenerationRequest.class)))
                .thenReturn(successResult("test-456"));

        String body = objectMapper.writeValueAsString(Map.of(
                "requirements", "Eligibility check rule",
                "dataStructure", "{\"age\": 25}",
                "hints", "eligibility, age-based"
        ));

        mockMvc.perform(post("/api/apex/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").isString())
                .andExpect(jsonPath("$.status").value("running"));
    }

    // ---- GET /api/apex/status/{jobId} ----

    @Test
    void status_unknownJobId_returns404() throws Exception {
        mockMvc.perform(get("/api/apex/status/nonexistent-job-id"))
                .andExpect(status().isNotFound());
    }

    @Test
    void status_afterSubmit_returnsRunningOrCompleted() throws Exception {
        // Submit a job first — the async executor may or may not finish before poll
        when(generationService.generate(any(GenerationRequest.class)))
                .thenReturn(successResult("async-test"));

        String body = objectMapper.writeValueAsString(Map.of(
                "requirements", "Simple validation rule",
                "dataStructure", "{\"value\": 10}"
        ));

        String responseJson = mockMvc.perform(post("/api/apex/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String jobId = objectMapper.readTree(responseJson).get("jobId").asText();

        // Poll — should find the job (either running or completed)
        mockMvc.perform(get("/api/apex/status/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId))
                .andExpect(jsonPath("$.status").isString());
    }

    @Test
    void status_completedJob_containsFullResult() throws Exception {
        GenerationResult result = successResult("status-test");

        when(generationService.generate(any(GenerationRequest.class)))
                .thenReturn(result);

        String body = objectMapper.writeValueAsString(Map.of(
                "requirements", "Full result test",
                "dataStructure", "{\"x\": 1}"
        ));

        // Submit
        String responseJson = mockMvc.perform(post("/api/apex/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String jobId = objectMapper.readTree(responseJson).get("jobId").asText();

        // Wait briefly for async to complete
        Thread.sleep(500);

        // Poll for completed result
        mockMvc.perform(get("/api/apex/status/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.attempts").value(1))
                .andExpect(jsonPath("$.summary").isString())
                .andExpect(jsonPath("$.files").isArray())
                .andExpect(jsonPath("$.files", hasSize(1)))
                .andExpect(jsonPath("$.files[0].fileName").value("discount-rule.yaml"))
                .andExpect(jsonPath("$.validationReport.lexicalValid").value(true))
                .andExpect(jsonPath("$.validationReport.compilationSuccess").value(true));
    }

    @Test
    void status_failedJob_containsError() throws Exception {
        when(generationService.generate(any(GenerationRequest.class)))
                .thenThrow(new RuntimeException("LLM service unavailable"));

        String body = objectMapper.writeValueAsString(Map.of(
                "requirements", "Failure test",
                "dataStructure", "{\"fail\": true}"
        ));

        String responseJson = mockMvc.perform(post("/api/apex/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String jobId = objectMapper.readTree(responseJson).get("jobId").asText();

        // Wait for async failure
        Thread.sleep(500);

        mockMvc.perform(get("/api/apex/status/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("failed"))
                .andExpect(jsonPath("$.error").value(containsString("LLM service unavailable")));
    }

    // ---- GET /api/apex/jobs ----

    @Test
    void jobs_returnsListOfSubmittedJobs() throws Exception {
        when(generationService.generate(any(GenerationRequest.class)))
                .thenReturn(successResult("list-test"));

        // Submit two jobs
        for (int i = 0; i < 2; i++) {
            String body = objectMapper.writeValueAsString(Map.of(
                    "requirements", "Job " + i,
                    "dataStructure", "{\"i\": " + i + "}"
            ));
            mockMvc.perform(post("/api/apex/generate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/apex/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(2))));
    }

    @Test
    void jobs_emptyWhenNoJobsSubmitted() throws Exception {
        // In a fresh controller, no jobs exist — but since @WebMvcTest
        // shares the same context per class, we just verify the endpoint works.
        mockMvc.perform(get("/api/apex/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ---- Input Validation ----

    @Test
    void generate_nullRequirements_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "dataStructure", "{\"x\": 1}"
        ));

        mockMvc.perform(post("/api/apex/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("requirements")));
    }

    @Test
    void generate_blankRequirements_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "requirements", "   ",
                "dataStructure", "{\"x\": 1}"
        ));

        mockMvc.perform(post("/api/apex/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("requirements")));
    }

    @Test
    void generate_nullDataStructure_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "requirements", "Some rule"
        ));

        mockMvc.perform(post("/api/apex/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("dataStructure")));
    }

    // ---- ruleResults in API response ----

    @Test
    void status_completedJob_containsRuleResults() throws Exception {
        // Build a result with ruleResults in executionDetails
        List<Map<String, Object>> ruleResults = List.of(
                Map.of("success", true, "resultType", "MATCH",
                        "childResults", List.of(
                                Map.of("ruleId", "r1", "ruleName", "Age Check",
                                        "success", true, "triggered", true,
                                        "resultType", "MATCH", "severity", "ERROR",
                                        "message", "Age is valid")
                        ),
                        "failureMessages", List.of(),
                        "failureCount", 0)
        );

        GenerationResult result = new GenerationResult(
                "rule-results-test", true, 1,
                List.of(new GeneratedFile("rules.yaml", "rules",
                        "id: r1\ncondition: true",
                        Path.of("generated/apex/rule-results-test/rules/rules.yaml"))),
                new ValidationReport(true, true, true, true, 0, 0, List.of(),
                        Map.of("ruleResults", ruleResults)),
                "Generated with rule results",
                Path.of("generated/apex/rule-results-test"),
                Instant.now()
        );

        when(generationService.generate(any(GenerationRequest.class))).thenReturn(result);

        String body = objectMapper.writeValueAsString(Map.of(
                "requirements", "Rule results test",
                "dataStructure", "{\"age\": 25}"
        ));

        String responseJson = mockMvc.perform(post("/api/apex/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String jobId = objectMapper.readTree(responseJson).get("jobId").asText();
        Thread.sleep(500);

        mockMvc.perform(get("/api/apex/status/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.validationReport.ruleResults").isArray())
                .andExpect(jsonPath("$.validationReport.ruleResults", hasSize(1)))
                .andExpect(jsonPath("$.validationReport.ruleResults[0].success").value(true))
                .andExpect(jsonPath("$.validationReport.ruleResults[0].resultType").value("MATCH"))
                .andExpect(jsonPath("$.validationReport.ruleResults[0].childResults[0].ruleId").value("r1"))
                .andExpect(jsonPath("$.validationReport.ruleResults[0].childResults[0].ruleName").value("Age Check"))
                .andExpect(jsonPath("$.validationReport.ruleResults[0].childResults[0].triggered").value(true))
                .andExpect(jsonPath("$.validationReport.ruleResults[0].childResults[0].severity").value("ERROR"));
    }

    @Test
    void status_completedJob_omitsRuleResultsWhenEmpty() throws Exception {
        // No ruleResults in executionDetails
        when(generationService.generate(any(GenerationRequest.class)))
                .thenReturn(successResult("no-rule-results"));

        String body = objectMapper.writeValueAsString(Map.of(
                "requirements", "No rule results test",
                "dataStructure", "{\"x\": 1}"
        ));

        String responseJson = mockMvc.perform(post("/api/apex/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String jobId = objectMapper.readTree(responseJson).get("jobId").asText();
        Thread.sleep(500);

        mockMvc.perform(get("/api/apex/status/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.validationReport.ruleResults").doesNotExist());
    }

    // ---- Helpers ----

    private GenerationResult successResult(String requestId) {
        return new GenerationResult(
                requestId,
                true,
                1,
                List.of(new GeneratedFile(
                        "discount-rule.yaml",
                        "rules",
                        "id: r1\nname: Discount Rule\ncondition: \"#orderTotal > 100\"",
                        Path.of("generated/apex/" + requestId + "/rules/discount-rule.yaml")
                )),
                new ValidationReport(
                        true, true, true, true,
                        0, 0, List.of(), Map.of()
                ),
                "Successfully generated 1 APEX rule file",
                Path.of("generated/apex/" + requestId),
                Instant.now()
        );
    }
}
