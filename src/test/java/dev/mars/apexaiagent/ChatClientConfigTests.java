package dev.mars.apexaiagent;

import dev.mars.apexaiagent.orchestration.ApexGenerationService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.DefaultChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the ChatClient bean is configured with the expected system prompt,
 * tools, and advisors. No LLM call is made — this inspects the wiring only.
 * Uses a dummy API key so no real credentials are needed.
 *
 * CRITICAL: also verifies that the REPL ChatClient and the APEX generation
 * ChatClient are independently configured (no shared-builder contamination).
 */
@SpringBootTest
@TestPropertySource(properties = {
		"spring.ai.openai-sdk.api-key=sk-test-dummy-key",
		"app.repl.enabled=false",
		"apex.knowledge.enabled=false"
})
class ChatClientConfigTests {

	@Autowired
	ChatClient chatClient;

	@Autowired
	ApexGenerationService apexGenerationService;

	@Test
	void systemPromptContainsWorkingDirectory() {
		var spec = (DefaultChatClient.DefaultChatClientRequestSpec) chatClient.prompt();
		assertThat(spec.getSystemText()).contains("Current directory:");
		assertThat(spec.getSystemText()).contains(System.getProperty("user.dir"));
		assertThat(spec.getSystemText()).contains("Operating system:");
		assertThat(spec.getSystemText()).contains(System.getProperty("os.name"));
	}

	@Test
	void hasExpectedTools() {
		var spec = (DefaultChatClient.DefaultChatClientRequestSpec) chatClient.prompt();
		var toolNames = spec.getToolCallbacks().stream()
				.map(tc -> tc.getToolDefinition().name())
				.toList();

		// FileSystemTools: Read, Write, Edit
		assertThat(toolNames).contains("Read", "Write", "Edit");
		// GrepTool
		assertThat(toolNames).contains("Grep");
		// GlobTool
		assertThat(toolNames).contains("Glob");
		// ShellTools: Bash, KillShell, BashOutput
		assertThat(toolNames).contains("Bash");
	}

	@Test
	void hasExpectedAdvisors() {
		var spec = (DefaultChatClient.DefaultChatClientRequestSpec) chatClient.prompt();
		var advisorTypes = spec.getAdvisors().stream()
				.map(a -> a.getClass().getSimpleName())
				.toList();

		assertThat(advisorTypes).contains("ToolCallAdvisor");
		assertThat(advisorTypes).contains("MessageChatMemoryAdvisor");
	}

	@Test
	void hasMultipleToolCallbacksRegistered() {
		var spec = (DefaultChatClient.DefaultChatClientRequestSpec) chatClient.prompt();
		// Four tool classes, each may register multiple callbacks
		assertThat(spec.getToolCallbacks()).hasSizeGreaterThanOrEqualTo(4);
	}

	// ---- Builder Isolation Tests ----
	// These tests catch the shared-builder contamination bug where both
	// the REPL ChatClient and APEX ChatClient mutated the same builder.

	@Test
	void replClient_doesNotHaveApexSystemPrompt() {
		var spec = (DefaultChatClient.DefaultChatClientRequestSpec) chatClient.prompt();
		// The REPL client should have the coding assistant prompt, NOT the APEX generation prompt
		assertThat(spec.getSystemText()).contains("helpful coding assistant");
		assertThat(spec.getSystemText()).doesNotContain("APEX Rules Configuration Generator");
	}

	@Test
	void apexClient_hasApexSystemPrompt() {
		var apexSpec = (DefaultChatClient.DefaultChatClientRequestSpec)
				apexGenerationService.getApexChatClient().prompt();
		// The APEX client should have the generation prompt, NOT the REPL prompt
		assertThat(apexSpec.getSystemText()).contains("APEX Rules Configuration Generator");
		assertThat(apexSpec.getSystemText()).doesNotContain("helpful coding assistant");
	}

	@Test
	void replClient_hasMemoryAdvisor_apexClient_doesNot() {
		// REPL client MUST have MessageChatMemoryAdvisor for conversation history
		var replAdvisors = ((DefaultChatClient.DefaultChatClientRequestSpec) chatClient.prompt())
				.getAdvisors().stream()
				.map(a -> a.getClass().getSimpleName())
				.toList();
		assertThat(replAdvisors).contains("MessageChatMemoryAdvisor");

		// APEX client must NOT have MessageChatMemoryAdvisor — it's stateless per-request
		var apexAdvisors = ((DefaultChatClient.DefaultChatClientRequestSpec)
				apexGenerationService.getApexChatClient().prompt())
				.getAdvisors().stream()
				.map(a -> a.getClass().getSimpleName())
				.toList();
		assertThat(apexAdvisors).doesNotContain("MessageChatMemoryAdvisor");
	}

	@Test
	void replClient_hasNoDuplicateAdvisors() {
		var advisors = ((DefaultChatClient.DefaultChatClientRequestSpec) chatClient.prompt())
				.getAdvisors().stream()
				.map(a -> a.getClass().getSimpleName())
				.toList();
		// Should have exactly one ToolCallAdvisor, not multiple from builder sharing
		long toolCallAdvisorCount = advisors.stream()
				.filter(n -> n.equals("ToolCallAdvisor"))
				.count();
		assertThat(toolCallAdvisorCount)
				.as("REPL client should have exactly 1 ToolCallAdvisor, not duplicates from shared builder")
				.isEqualTo(1);
	}

	@Test
	void apexClient_hasNoDuplicateAdvisors() {
		var advisors = ((DefaultChatClient.DefaultChatClientRequestSpec)
				apexGenerationService.getApexChatClient().prompt())
				.getAdvisors().stream()
				.map(a -> a.getClass().getSimpleName())
				.toList();
		// Should have exactly one ToolCallAdvisor
		long toolCallAdvisorCount = advisors.stream()
				.filter(n -> n.equals("ToolCallAdvisor"))
				.count();
		assertThat(toolCallAdvisorCount)
				.as("APEX client should have exactly 1 ToolCallAdvisor, not duplicates from shared builder")
				.isEqualTo(1);
	}

	@Test
	void replClient_hasGenerateCommand_apexClient_doesNot() {
		// REPL client should have the GenerateApexRules tool
		var replTools = ((DefaultChatClient.DefaultChatClientRequestSpec) chatClient.prompt())
				.getToolCallbacks().stream()
				.map(tc -> tc.getToolDefinition().name())
				.toList();
		assertThat(replTools).contains("GenerateApexRules");

		// APEX client should NOT have GenerateApexRules (would cause infinite recursion)
		var apexTools = ((DefaultChatClient.DefaultChatClientRequestSpec)
				apexGenerationService.getApexChatClient().prompt())
				.getToolCallbacks().stream()
				.map(tc -> tc.getToolDefinition().name())
				.toList();
		assertThat(apexTools).doesNotContain("GenerateApexRules");
	}

	@Test
	void apexClient_hasOnlyApexTools() {
		var apexTools = ((DefaultChatClient.DefaultChatClientRequestSpec)
				apexGenerationService.getApexChatClient().prompt())
				.getToolCallbacks().stream()
				.map(tc -> tc.getToolDefinition().name())
				.toList();

		// Should have the 5 APEX tools
		assertThat(apexTools).contains(
				"ApexValidateLexical", "ApexCompile", "ApexValidateAndCompile",  // ApexCompileTool
				"ApexExecute",                                                    // ApexExecuteTool
				"ApexAssertExpectations"                                           // ApexExpectationTool
		);

		// Should NOT have file system / shell tools (those are REPL-only)
		assertThat(apexTools).doesNotContain("Read", "Write", "Edit", "Grep", "Glob", "Bash");
	}
}
