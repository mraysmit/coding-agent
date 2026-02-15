# Coding Agent

A CLI-powered coding assistant built with Spring Boot and Spring AI. In ~80 lines of Java, this project wires up
GPT-4o with file system tools, grep, glob, and shell access to create an interactive agent that can read, search,
and reason about your codebase.

## What It Does

Run the app, point it at a project, and ask questions in plain English:

```
🤖 Coding Agent Ready. Ask me anything about your codebase!

> What does the main application class do?
> Find all TODO comments in the project
> Show me how error handling works in the service layer
> Run the tests and tell me if anything fails
```

The model decides which tools to call, chains them together, and maintains conversational memory across your session.

## Getting Started

### Prerequisites

- Java 23+
- An [OpenAI API key](https://platform.openai.com/api-keys)

### Run It

```bash
export OPENAI_API_KEY=your-key-here
./mvnw spring-boot:run
```

Or build and run the jar directly:

```bash
./mvnw clean package -DskipTests
java -jar target/codingagent-0.0.1-SNAPSHOT.jar
```

Type `exit` to quit.

## How It Works

The entire agent lives in `Application.java`. A `ChatClient` is configured with:

- **System prompt** with the current working directory and operating system for context
- **Tools** from [spring-ai-agent-utils](https://github.com/springaicommunity/spring-ai-agent-utils) — `FileSystemTools`, `GrepTool`, `GlobTool`, and `ShellTools`
- **Chat memory** via `MessageWindowChatMemory` (50-message window) so the agent remembers what you've already discussed
- **Tool call advisor** that lets the model autonomously decide which tools to invoke

The REPL loop reads user input, sends it to the model with tool context, and prints the response. The model handles the rest — deciding when to read a file, search for a pattern, or run a command.

## Tech Stack

| Dependency | Version |
|---|---|
| Spring Boot | 4.0.2 |
| Spring AI | 2.0.0-M2 |
| Spring AI Agent Utils | 0.4.2 |
| Java | 23 |
| Model | GPT-4o (OpenAI) |

## Testing

The project includes three test classes:

- **`ChatClientConfigTests`** — verifies the ChatClient bean is wired with the correct system prompt, tools, and advisors (no LLM call needed, uses a dummy API key)
- **`ApplicationTests`** — smoke test that makes a real LLM call; only runs when `OPENAI_API_KEY` is set
- **`ReplTests`** — lightweight unit tests for the REPL loop control flow (no Spring context)

```bash
./mvnw test
```

## Beyond the Coding Agent: Where This Pattern Shines

This demo builds a coding assistant, but the real takeaway is the **pattern**: an LLM with tools, memory, and a conversational loop — all wired up in Spring Boot. Here are some practical places this same architecture fits:

### DevOps & Infrastructure

Give an agent access to shell commands, log files, and config files. Ask it to check service health, tail logs for errors, restart processes, or explain why a deployment failed. Pair it with `kubectl` or Docker CLI access and you have an on-call assistant.

### Log Analysis & Debugging

Point the agent at application logs and let it search for patterns, correlate timestamps, and summarize what went wrong. Instead of manually grepping through gigabytes of logs you describe the symptom and the agent hunts for the root cause.

### Database Exploration

Swap the file system tools for JDBC-backed tools. The agent reads schemas, runs queries, and explains results in plain language. Useful for onboarding onto an unfamiliar database or quickly answering ad hoc data questions without writing SQL from scratch.

### Documentation & Knowledge Bases

Point it at a docs directory, wiki export, or markdown knowledge base. The agent searches, cross-references, and synthesizes answers. Great for internal tooling where the docs exist but nobody can find anything.

### Security Auditing

An agent that scans dependency files, searches for hardcoded secrets, checks for common vulnerability patterns, and runs static analysis tools. Combine `GrepTool` with shell access to tools like `trivy` or `semgrep` for an interactive security review.

### Data Pipeline Monitoring

Give the agent access to pipeline configs, output directories, and health check endpoints. It can verify data freshness, check row counts, compare expected vs. actual schemas, and flag anomalies — all through a conversational interface.

### Legacy Code Exploration

Drop this into an unfamiliar or inherited codebase. The agent can map out the project structure, trace call chains, find dead code, and explain what modules do. Much faster than manually reading through thousands of files when you're getting up to speed.

### Test Generation

The agent reads source files, understands the logic, and generates test cases. It can also run the tests it creates and iterate on failures — a tight feedback loop without leaving the terminal.

## Swapping Models and Providers

Spring AI abstracts the model provider, so switching from OpenAI to Anthropic, Ollama, or any other supported provider is a dependency and config change — no code changes required.

## Resources

- [Spring AI Reference Documentation](https://docs.spring.io/spring-ai/reference/)
- [Spring AI Agent Utils](https://github.com/springaicommunity/spring-ai-agent-utils)
- [OpenAI API](https://platform.openai.com/docs)
