package dev.mars.codingagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test that makes a real LLM call. Only runs when the OPENAI_API_KEY
 * environment variable is set, so CI without credentials skips it automatically.
 */
@SpringBootTest
@TestPropertySource(properties = "app.repl.enabled=false")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class ApplicationTests {

	@Autowired
	ChatClient chatClient;

	@Test
	void smokeTestRealLlmCall() {
		String response = chatClient
				.prompt("Respond with exactly: HELLO")
				.toolContext(Map.of("workingDir", System.getProperty("user.dir")))
				.call()
				.content();

		assertThat(response).isNotNull();
		assertThat(response).containsIgnoringCase("HELLO");
	}

}
