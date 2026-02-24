# Copilot Instructions for APEX AI Agent

## Project Overview

This is an AI-powered APEX rules agent built with **Spring Boot 4.0.2** and **Spring AI 2.0.0-M2**. It generates, validates, and executes APEX YAML business rule configurations from natural language requirements, using an LLM with file system tools, grep, glob, shell access, and specialized APEX tools.

## Tech Stack

- **Java 23** (source level)
- **Spring Boot 4.0.2**
- **Spring AI 2.0.0-M2** (via BOM)
- **Spring AI Agent Utils 0.4.2** (`org.springaicommunity:spring-ai-agent-utils`) — provides `FileSystemTools`, `GrepTool`, `GlobTool`, `ShellTools`
- **OpenAI GPT-4o** as the backing model (configured via `spring.ai.openai-sdk`)
- **Maven** as the build tool (with Maven Wrapper)

## Architecture

- The application is a Spring Boot CLI app using `CommandLineRunner`.
- A `ChatClient` is built with:
  - A system prompt injected with the current working directory.
  - Default tools: `FileSystemTools`, `GrepTool`, `GlobTool`, `ShellTools`.
  - `ToolCallAdvisor` for autonomous tool invocation (conversation history disabled at advisor level).
  - `MessageChatMemoryAdvisor` with a 50-message sliding window via `MessageWindowChatMemory`.
- A REPL loop reads user input from `System.in`, sends it to the model with tool context (`workingDir`), and prints the response.
- The user types `exit` to quit.

## Code Conventions

- Package root: `dev.mars.apexaiagent`
- Single-class application pattern — keep it concise and self-contained.
- Use Spring AI's fluent `ChatClient.Builder` API for configuration.
- Use text blocks (`"""`) for multi-line strings like system prompts.
- Tools are created via their builder patterns (e.g., `FileSystemTools.builder().build()`).
- Pass runtime context to tools via `toolContext(Map.of(...))`.

## Configuration

- Application config is in `src/main/resources/application.yaml`.
- The OpenAI API key is read from the `OPENAI_API_KEY` environment variable.
- Model provider is abstracted by Spring AI — switching providers requires only dependency and config changes, no code changes.

## Building & Running

```bash
# Build
./mvnw clean package

# Run
export OPENAI_API_KEY=your-key-here
./mvnw spring-boot:run
```

## Key Dependencies to Know

| Dependency | Purpose |
|---|---|
| `spring-boot-starter` | Core Spring Boot (no web server) |
| `spring-ai-starter-model-openai-sdk` | OpenAI model integration via Spring AI |
| `spring-ai-agent-utils` | Community-provided agent tools (file I/O, grep, glob, shell) |
| `spring-boot-starter-test` | Testing support |

## When Making Changes

- Preserve the concise, single-file design unless there's a strong reason to split.
- New tools should follow the same builder pattern and be added to `defaultTools(...)`.
- New advisors should be added to `defaultAdvisors(...)`.
- Keep the REPL loop simple — the LLM handles orchestration.
- If adding new dependencies, manage versions via the Spring AI BOM when possible.
- The system prompt should always include the current working directory for file-based tools to function correctly.
