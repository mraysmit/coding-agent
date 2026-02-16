package dev.mars.codingagent;

import org.springaicommunity.agent.tools.FileSystemTools;
import org.springaicommunity.agent.tools.GlobTool;
import org.springaicommunity.agent.tools.GrepTool;
import org.springaicommunity.agent.tools.ShellTools;
import dev.mars.codingagent.tools.ApexCompileTool;
import dev.mars.codingagent.tools.ApexExecuteTool;
import dev.mars.codingagent.tools.ApexExpectationTool;
import dev.mars.codingagent.tools.ApexSyntaxTool;
import dev.mars.codingagent.tools.ApexExampleRetrievalTool;
import dev.mars.codingagent.orchestration.ApexGenerateCommand;
import dev.mars.codingagent.orchestration.ApexGenerationService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Scanner;

@SpringBootApplication
public class Application {

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

	@Bean
	ApexGenerationService apexGenerationService(ChatModel chatModel) {
		return ApexGenerationService.builder()
				.chatModel(chatModel)
				.outputDir(Path.of("generated", "apex"))
				.maxAttempts(3)
				.build();
	}

	@Bean
	ChatClient chatClient(ChatClient.Builder builder, ApexGenerationService apexGenerationService) {
		return builder.clone()
				.defaultSystem("""
                    You are a helpful coding assistant. You have access to tools
                    for reading files, searching code, running shell commands,
                    and editing files. Use them to help the user with their codebase.

                    You also have access to APEX rules tools for validating, executing,
                    and generating APEX YAML business rule configurations. When the user
                    asks to create or generate APEX rules, use the GenerateApexRules tool
                    which runs the full generation pipeline.

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
						ShellTools.builder().build(),
						ApexCompileTool.builder().build(),
						ApexExecuteTool.builder().build(),
						ApexExpectationTool.builder().build(),
						ApexSyntaxTool.builder().build(),
						ApexExampleRetrievalTool.builder().build(),
						new ApexGenerateCommand(apexGenerationService)
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
			if (input.isBlank()) continue;

			String response = chatClient.prompt(input)
						.toolContext(Map.of("workingDir", System.getProperty("user.dir")))
						.call().content();
			out.println("\n" + (response != null ? response : "[No response from model]"));
		}
	}
}
