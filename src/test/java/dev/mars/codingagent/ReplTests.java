package dev.mars.codingagent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;
import java.util.Scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Lightweight unit tests for the REPL loop control flow.
 * No Spring context needed.
 */
class ReplTests {

	@Test
	void exitCommandTerminatesWithoutCallingModel() {
		ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);

		Scanner scanner = new Scanner(new ByteArrayInputStream("exit\n".getBytes()));
		ByteArrayOutputStream output = new ByteArrayOutputStream();

		Application.runRepl(chatClient, scanner, new PrintStream(output));

		assertThat(output.toString()).contains("Coding Agent Ready");
		verify(chatClient, never()).prompt(anyString());
	}

	@Test
	void userInputIsSentToModelAndResponsePrinted() {
		ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
		when(chatClient.prompt(anyString()).toolContext(any(Map.class)).call().content())
				.thenReturn("Here is the answer");
		clearInvocations(chatClient);

		String input = "What does this code do?\nexit\n";
		Scanner scanner = new Scanner(new ByteArrayInputStream(input.getBytes()));
		ByteArrayOutputStream output = new ByteArrayOutputStream();

		Application.runRepl(chatClient, scanner, new PrintStream(output));

		assertThat(output.toString()).contains("Here is the answer");
	}

	@Test
	void noInteractiveTerminalExitsGracefully() {
		ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);

		Scanner scanner = new Scanner(new ByteArrayInputStream(new byte[0]));
		ByteArrayOutputStream output = new ByteArrayOutputStream();

		Application.runRepl(chatClient, scanner, new PrintStream(output));

		assertThat(output.toString()).contains("No interactive terminal detected");
		verify(chatClient, never()).prompt(anyString());
	}
}
