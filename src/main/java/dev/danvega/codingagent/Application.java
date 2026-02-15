package dev.mark.codingagent;

import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GlobTool;
import org.springaicommunity.agent.tools.GrepTool;
import org.springaicommunity.agent.tools.ShellTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.io.PrintStream;
import java.util.Map;
import java.util.Scanner;

@SpringBootApplication
public class Application {

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

	@Bean
	ChatClient chatClient(ChatClient.Builder builder) {
		return builder
				.defaultSystem("""
                    You are a helpful coding assistant. You have access to tools
                    for reading files, searching code, running shell commands,
                    and editing files. Use them to help the user with their codebase.

                    Current directory: %s
                    Operating system: %s
                    
                    When running shell commands, use commands appropriate for the
                    operating system. On Windows use PowerShell commands (e.g.
                    Get-ChildItem instead of ls, Get-Content instead of cat).
                    """.formatted(System.getProperty("user.dir"), System.getProperty("os.name")))
				.defaultTools(
						FileSystemTools.builder().build(),
						GrepTool.builder().build(),
						GlobTool.builder().build(),
						ShellTools.builder().build()
				)
				.defaultAdvisors(
						ToolCallAdvisor.builder().conversationHistoryEnabled(false).build(),
						MessageChatMemoryAdvisor.builder(
								MessageWindowChatMemory.builder().maxMessages(50).build()
						).build()
				)
				.build();
	}

	@Bean
	@ConditionalOnProperty(name = "app.repl.enabled", havingValue = "true", matchIfMissing = true)
	CommandLineRunner demo(ChatClient chatClient) {
		return args -> runRepl(chatClient, new Scanner(System.in), System.out);
	}

	static void runRepl(ChatClient chatClient, Scanner scanner, PrintStream out) {
		out.println("🤖 Coding Agent Ready. Ask me anything about your codebase!");

		while (true) {
			out.print("\n> ");
			out.flush();
			if (!scanner.hasNextLine()) {
				out.println("\nNo interactive terminal detected. Run with: java -jar target/codingagent-0.0.1-SNAPSHOT.jar");
				break;
			}
			String input = scanner.nextLine();
			if ("exit".equalsIgnoreCase(input.trim())) break;

			String response = chatClient.prompt(input)
						.toolContext(Map.of("workingDir", System.getProperty("user.dir")))
						.call().content();
			out.println("\n" + response);
		}
	}
}
