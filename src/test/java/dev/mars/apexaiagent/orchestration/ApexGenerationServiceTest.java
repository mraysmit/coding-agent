package dev.mars.apexaiagent.orchestration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for ApexGenerationService — system prompt loading and prompt building.
 * Does NOT call the LLM — tests only the deterministic parts.
 */
class ApexGenerationServiceTest {

    @Test
    void loadSystemPrompt_containsWorkflow() {
        String prompt = ApexGenerationService.loadSystemPrompt("test-req-123");

        assertThat(prompt).contains("APEX Rules Configuration Generator");
        assertThat(prompt).contains("Step 1: PLAN");
        assertThat(prompt).contains("Step 2: RETRIEVE");
        assertThat(prompt).contains("Step 3: AUTHOR");
        assertThat(prompt).contains("Step 4: VALIDATE");
        assertThat(prompt).contains("Step 5: FIX");
        assertThat(prompt).contains("Step 6: PACKAGE");
    }

    @Test
    void loadSystemPrompt_substitutesPlatformVars() {
        String prompt = ApexGenerationService.loadSystemPrompt("req-abc");

        assertThat(prompt).contains(System.getProperty("user.dir"));
        assertThat(prompt).contains(System.getProperty("os.name"));
        assertThat(prompt).contains("req-abc");
    }

    @Test
    void loadSystemPrompt_containsStrictRules() {
        String prompt = ApexGenerationService.loadSystemPrompt("req-x");

        assertThat(prompt).contains("NEVER invent APEX keywords");
        assertThat(prompt).contains("ALWAYS retrieve a template");
        assertThat(prompt).contains("ALWAYS validate");
        assertThat(prompt).contains("ALWAYS execute");
    }

    @Test
    void loadSystemPrompt_containsSpelReference() {
        String prompt = ApexGenerationService.loadSystemPrompt("req-y");

        assertThat(prompt).contains("SpEL");
        assertThat(prompt).contains("#fieldName");
        assertThat(prompt).contains("NEVER use `##`");
    }

    @Test
    void loadSystemPrompt_containsOutputFormat() {
        String prompt = ApexGenerationService.loadSystemPrompt("req-z");

        assertThat(prompt).contains("=== GENERATED YAML ===");
        assertThat(prompt).contains("=== TEST DATA ===");
        assertThat(prompt).contains("=== VALIDATION REPORT ===");
        assertThat(prompt).contains("=== SUMMARY ===");
    }

    @Test
    void loadSystemPrompt_containsRuleSemantics() {
        String prompt = ApexGenerationService.loadSystemPrompt("req-s");

        assertThat(prompt).contains("condition is TRUE");
        assertThat(prompt).contains("rule PASSES");
        assertThat(prompt).contains("condition is FALSE");
        assertThat(prompt).contains("rule FAILS");
        assertThat(prompt).contains("name is required");
    }

    @Test
    void loadSystemPrompt_containsRetryPolicy() {
        String prompt = ApexGenerationService.loadSystemPrompt("req-r");

        assertThat(prompt).contains("Maximum 3 attempts");
        assertThat(prompt).contains("Retry Policy");
    }

    @Test
    void loadSystemPrompt_nullRequestId_handlesGracefully() {
        String prompt = ApexGenerationService.loadSystemPrompt(null);

        assertThat(prompt).contains("unknown");
        assertThat(prompt).doesNotContain("{{requestId}}");
    }
}
