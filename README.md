# Coding Agent

An AI coding assistant that runs in your terminal. Ask it questions about your codebase and it will read files, search code, and run commands to find the answer.

Built with Spring Boot 4.0.2 and Spring AI 2.0.0-M2 in a single Java file.

## Quick Start

```bash
export OPENAI_API_KEY=your-key-here
./mvnw spring-boot:run
```

```
🤖 Coding Agent Ready. Ask me anything about your codebase!

> explain the authentication flow
> find all usages of UserService
> run the tests and summarize failures
> what does this regex in config.yaml do?
```

Type `exit` to quit.

### Build a Standalone Jar

```bash
./mvnw clean package -DskipTests
java -jar target/codingagent-0.0.1-SNAPSHOT.jar
```

## Prerequisites

- Java 23+
- [OpenAI API key](https://platform.openai.com/api-keys)

## Architecture

Everything lives in [`Application.java`](src/main/java/dev/danvega/codingagent/Application.java). The `ChatClient` bean is configured with:

| Component | Purpose |
|---|---|
| System prompt | Injects working directory and OS so the model knows where it is |
| `FileSystemTools` | Read, write, and list files |
| `GrepTool` | Search file contents by pattern |
| `GlobTool` | Find files by name/path pattern |
| `ShellTools` | Run shell commands |
| `MessageWindowChatMemory` | 50-message sliding window for conversational context |
| `ToolCallAdvisor` | Lets the model autonomously chain tool calls |

A REPL loop reads input, sends it to GPT-4o with tool context, and prints the response. The model decides which tools to invoke and in what order.

## Dependencies

| Library | Version |
|---|---|
| Spring Boot | 4.0.2 |
| Spring AI | 2.0.0-M2 |
| [spring-ai-agent-utils](https://github.com/springaicommunity/spring-ai-agent-utils) | 0.4.2 |
| Java | 23 |

## Tests

```bash
./mvnw test
```

| Test class | What it covers |
|---|---|
| `ChatClientConfigTests` | Verifies ChatClient wiring (system prompt, tools, advisors). No LLM call. |
| `ReplTests` | REPL control flow (exit, empty input, EOF). No Spring context. |
| `ApplicationTests` | End-to-end smoke test with a real LLM call. Requires `OPENAI_API_KEY`. |

## Swapping Models

Spring AI abstracts the model provider. To switch from OpenAI to Anthropic, Ollama, or another supported backend, change the dependency and config — no code changes needed.

## Links

- [Spring AI docs](https://docs.spring.io/spring-ai/reference/)
- [spring-ai-agent-utils](https://github.com/springaicommunity/spring-ai-agent-utils)
- [OpenAI API reference](https://platform.openai.com/docs)
