package dev.mars.codingagent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.DefaultChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the ChatClient bean is configured with the expected system prompt,
 * tools, and advisors. No LLM call is made — this inspects the wiring only.
 * Uses a dummy API key so no real credentials are needed.
 */
@SpringBootTest
@TestPropertySource(properties = {
		"spring.ai.openai-sdk.api-key=sk-test-dummy-key",
		"app.repl.enabled=false"
})
class ChatClientConfigTests {

	@Autowired
	ChatClient chatClient;

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
}
