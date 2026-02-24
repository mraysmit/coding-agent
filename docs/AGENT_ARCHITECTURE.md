# APEX AI Coding Agent — System Architecture Guide

Author 

A detailed guide to how the **apex-ai-agent** project works, how it integrates with the **apex-rules-engine**, and how the hybrid RAG knowledge system enables accurate APEX YAML generation.

---

## Table of Contents

1. [Overview](#overview)
2. [Tech Stack](#tech-stack)
3. [Project Structure](#project-structure)
4. [Two Chat Clients, Two Personalities](#two-chat-clients-two-personalities)
5. [How the APEX Generation Pipeline Works](#how-the-apex-generation-pipeline-works)
6. [Tool Inventory](#tool-inventory)
7. [The Three Knowledge Channels (Hybrid RAG)](#the-three-knowledge-channels-hybrid-rag)
8. [Vector Store Ingestion Pipeline](#vector-store-ingestion-pipeline)
9. [Interaction with apex-rules-engine](#interaction-with-apex-rules-engine)
10. [Web UI and REST API](#web-ui-and-rest-api)
11. [Configuration Reference](#configuration-reference)
12. [Building and Running](#building-and-running)
13. [Testing Strategy](#testing-strategy)

---

## Overview

The **apex-ai-agent** is an AI-powered coding assistant built on Spring Boot and Spring AI. Its primary mission is to **generate, validate, and execute APEX YAML business rule configurations** from natural language requirements.

It works by giving an LLM (GPT-4o) access to a curated set of tools that can:
- Look up APEX syntax rules and templates
- Search hundreds of real APEX YAML examples
- Perform semantic search across all APEX documentation
- Compile and validate generated YAML using the real APEX compiler
- Execute rules against test data using the real APEX engine
- Assert expected business outcomes programmatically

The LLM orchestrates these tools autonomously, following a strict plan → retrieve → author → validate → fix → package workflow.

---

## Tech Stack

| Component | Technology | Version |
|---|---|---|
| Runtime | Java | 23 |
| Framework | Spring Boot | 4.0.2 |
| AI Framework | Spring AI | 2.0.0-M2 |
| LLM | OpenAI GPT-4o | via `spring-ai-starter-model-openai-sdk` |
| Embeddings | OpenAI `text-embedding-3-small` | via same SDK |
| Vector Store | Spring AI `SimpleVectorStore` | In-memory with JSON persistence |
| Agent Tools | `spring-ai-agent-utils` | 0.4.2 (FileSystem, Grep, Glob, Shell) |
| Rules Engine | `apex-core` + `apex-compiler` | 1.0-SNAPSHOT (local install) |
| Build | Maven | with Maven Wrapper |
| Web UI | Static HTML/CSS/JS | served by Spring Boot embedded Tomcat |

---

## Project Structure

```
apex-ai-agent/
├── src/main/java/dev/mars/apexaiagent/
│   ├── Application.java                    # Spring Boot entry point, bean wiring
│   ├── rag/
│   │   └── ApexKnowledgeIngester.java      # Vector store ingestion pipeline
│   ├── tools/
│   │   ├── ApexCompileTool.java            # Lexical validation + compilation
│   │   ├── ApexExecuteTool.java            # Rule execution against test data
│   │   ├── ApexExpectationTool.java        # Business outcome assertions
│   │   ├── ApexSyntaxTool.java             # Schema templates + required fields
│   │   ├── ApexExampleRetrievalTool.java   # Tag-based example search (589 files)
│   │   └── ApexKnowledgeSearchTool.java    # Semantic search (vector store)
│   ├── orchestration/
│   │   ├── ApexGenerationService.java      # Generation pipeline orchestrator
│   │   ├── ApexGenerateCommand.java        # Tool wrapper for REPL/Web access
│   │   ├── GenerationRequest.java          # Request record
│   │   ├── GenerationResult.java           # Result record with validation report
│   │   └── OutputPackager.java             # Parses LLM output into files
│   └── web/
│       └── ApexGenerationController.java   # REST API for web UI
├── src/main/resources/
│   ├── application.yaml                    # Spring config
│   ├── prompts/
│   │   └── apex-generation-system.txt      # APEX generation system prompt
│   ├── static/
│   │   └── index.html                      # Web UI
│   └── logback.xml                         # Logging config
├── knowledge/
│   ├── example-index.json                  # Pre-built index of 589 YAML files
│   ├── apex-syntax-compact.yaml            # APEX syntax reference artifact
│   └── apex-vector-store.json              # Persisted vector embeddings (generated)
└── pom.xml
```

---

## Two Chat Clients, Two Personalities

The application creates **two independent ChatClient instances** with deliberately different tool sets and behaviors:

### 1. REPL/Web ChatClient (general assistant)

Defined in `Application.chatClient()`. This is the conversational client used by the CLI REPL and the Web UI.

**Tools available:**
- `FileSystemTools` — Read, Write, Edit files
- `GrepTool`, `GlobTool` — Code search and file matching
- `ShellTools` — Run shell commands
- All 5 APEX validation/syntax/search tools
- `ApexKnowledgeSearchTool` — Semantic search (vector store)
- `GenerateApexRules` — Delegates to the generation pipeline

**Advisors:**
- `ToolCallAdvisor` (with `conversationHistoryEnabled=false`)
- `MessageChatMemoryAdvisor` (50-message sliding window)

**Key design choice:** This client includes `GenerateApexRules` so the user can say "generate rules for credit scoring" and the REPL agent will delegate to the pipeline.

### 2. APEX Generation ChatClient (specialist)

Defined inside `ApexGenerationService.Builder.build()`. This is the specialist client used only during the generation pipeline.

**Tools available:**
- `ApexCompileTool` — Validate lexical, compile, validate-and-compile
- `ApexExecuteTool` — Execute rules, execute batch
- `ApexExpectationTool` — Assert expected outcomes
- `ApexSyntaxTool` — Templates, required fields, SpEL rules
- `ApexExampleRetrievalTool` — Tag-based search
- `ApexKnowledgeSearchTool` — Semantic search (when vector store provided)

**NOT included:**
- FileSystem/Grep/Glob/Shell tools (unnecessary for generation)
- `GenerateApexRules` (would cause infinite recursion)
- `MessageChatMemoryAdvisor` (stateless per-request, no conversation leakage)

**Advisors:**
- `ToolCallAdvisor` (with `conversationHistoryEnabled=true` — required for OpenAI multi-turn tool calls)

**Why two clients?** Builder isolation. Early versions had a bug where both clients shared the same `ChatClient.Builder`, causing auto-configured advisors to leak between them. The APEX client is now built from `ChatClient.builder(chatModel)` (fresh) rather than `builder.clone()`.

---

## How the APEX Generation Pipeline Works

When a user submits a generation request (via CLI, Web UI, or REST API), this is what happens:

```
User Request
    │
    ▼
┌──────────────────────────┐
│  ApexGenerateCommand     │   ← Tool called by REPL client
│  (or Controller REST)    │
└──────────┬───────────────┘
           │
           ▼
┌──────────────────────────┐
│  ApexGenerationService   │   ← Orchestrates the pipeline
│  .generate(request)      │
│                          │
│  ┌─ Attempt Loop ──────┐│
│  │ 1. Build user prompt ││
│  │ 2. Send to LLM      ││
│  │ 3. LLM uses tools:  ││
│  │    - Plan            ││
│  │    - Retrieve (RAG)  ││   ← LLM autonomously calls tools
│  │    - Author YAML     ││
│  │    - Validate        ││
│  │    - Fix & retry     ││
│  │    - Package output  ││
│  │ 4. Parse response    ││
│  └──────────────────────┘│
│                          │
│  Up to 3 outer retries   │
└──────────┬───────────────┘
           │
           ▼
┌──────────────────────────┐
│  OutputPackager           │   ← Extracts YAML, test data, validation
│  .packageOutput()         │      results from LLM's structured response
│                           │
│  Writes to:               │
│  generated/apex/{id}/     │
│    rules/*.yaml           │
│    data/test-data.json    │
│    reports/validation.json│
└──────────┬────────────────┘
           │
           ▼
     GenerationResult
     {success, files[], validationReport, ruleResults[], summary}
```

### The LLM's Internal Workflow

The LLM follows a 6-step workflow encoded in the system prompt (`apex-generation-system.txt`):

1. **PLAN** — Analyze requirements, determine document types, features, test scenarios
2. **RETRIEVE** — Call syntax tools, example search, and semantic search to gather knowledge
3. **AUTHOR** — Write YAML starting from templates, filling in rules/enrichments/metadata
4. **VALIDATE** — Run `validateAndCompile()`, `evaluateYaml()`, `assertExpectedOutcomes()`
5. **FIX** — If validation fails, read error classifications and make targeted fixes
6. **PACKAGE** — Format the final response with labeled sections for the OutputPackager

This all happens in a single LLM turn with multiple tool calls. The `ToolCallAdvisor` handles the tool execution loop automatically — the LLM decides which tools to call and in what order.

---

## Tool Inventory

### Validation & Execution Tools (backed by apex-rules-engine)

| Tool | Method | What it does |
|---|---|---|
| `ApexValidateLexical` | `ApexCompileTool.validateLexical()` | Parses YAML and checks for syntax errors, unknown keywords, structural issues. Returns JSON with errors, warnings, errorClassification. |
| `ApexCompile` | `ApexCompileTool.compile()` | Compiles YAML into an executable rule set. Catches SpEL syntax errors, missing fields, schema violations. |
| `ApexValidateAndCompile` | `ApexCompileTool.validateAndCompile()` | Runs both lexical validation and compilation in one call. |
| `ApexExecute` | `ApexExecuteTool.evaluateYaml()` | Compiles YAML and executes it against a JSON test payload. Returns per-rule results (ruleId, ruleName, triggered, message, severity). |
| `ApexExecuteBatch` | `ApexExecuteTool.evaluateBatch()` | Executes rules against an array of test payloads. |
| `ApexAssertExpectations` | `ApexExpectationTool.assertExpectedOutcomes()` | Compares actual execution results against expected outcomes (rule pass/fail, specific messages, severity). |

### Knowledge Retrieval Tools

| Tool | Method | What it does |
|---|---|---|
| `ApexGetDocTypeTemplate` | `ApexSyntaxTool.getDocTypeTemplate()` | Returns the canonical YAML template for a document type (rule-config, scenario, component, etc.) |
| `ApexGetRequiredFields` | `ApexSyntaxTool.getRequiredFields()` | Returns mandatory fields for a document type |
| `ApexGetKeywordRules` | `ApexSyntaxTool.getKeywordRules()` | Returns valid keywords for a specific APEX section |
| `ApexGetSpelRules` | `ApexSyntaxTool.getSpelRules()` | Returns SpEL expression rules and patterns |
| `ApexSearchExamples` | `ApexExampleRetrievalTool.searchExamples()` | Tag-based search over 589 indexed YAML files. Filters by feature tags and document type. Ranks by feature overlap score. |
| `ApexCorpusStats` | `ApexExampleRetrievalTool.getCorpusStats()` | Aggregate statistics about the example corpus |
| `ApexSemanticSearch` | `ApexKnowledgeSearchTool.semanticSearch()` | Vector similarity search across all documentation and YAML examples. Natural language queries. |

### Orchestration Tools (REPL only)

| Tool | Method | What it does |
|---|---|---|
| `GenerateApexRules` | `ApexGenerateCommand.generateApexRules()` | Runs the full generation pipeline from requirements + data structure |
| `GenerateApexRulesWithHints` | `ApexGenerateCommand.generateApexRulesWithHints()` | Same, with additional hints |

---

## The Three Knowledge Channels (Hybrid RAG)

The LLM has access to three complementary knowledge channels, each optimized for different query types:

```
                    ┌─────────────────────────────────────────┐
                    │             LLM (GPT-4o)                │
                    │                                         │
                    │  "I need to generate APEX YAML with     │
                    │   enrichment groups and field mappings"  │
                    └───┬───────────────┬───────────────┬─────┘
                        │               │               │
        ┌───────────────┘               │               └───────────────┐
        ▼                               ▼                               ▼
┌───────────────────┐   ┌───────────────────────────┐   ┌──────────────────────┐
│  Channel 1:       │   │  Channel 2:               │   │  Channel 3:          │
│  SYNTAX TOOL      │   │  TAG-BASED SEARCH         │   │  SEMANTIC SEARCH     │
│                   │   │                           │   │  (Vector Store RAG)  │
│  ApexSyntaxTool   │   │  ApexExampleRetrievalTool │   │  ApexKnowledgeSearch │
│                   │   │                           │   │                      │
│  Structured       │   │  Pre-indexed 589 YAML     │   │  ~1000+ embedded     │
│  schema data:     │   │  files with feature tags, │   │  document chunks     │
│  - Templates      │   │  complexity, doc types    │   │  from markdown docs  │
│  - Required fields│   │                           │   │  and YAML examples   │
│  - SpEL rules     │   │  Query by:                │   │                      │
│  - Valid keywords │   │  - Feature tags           │   │  Query by:           │
│                   │   │  - Document type          │   │  - Natural language  │
│  Best for:        │   │  - Complexity level       │   │  - Conceptual ideas  │
│  "What fields     │   │                           │   │  - How-to questions  │
│  does a rule-     │   │  Best for:                │   │                      │
│  config need?"    │   │  "Show me examples with   │   │  Best for:           │
│                   │   │  rules AND enrichments"   │   │  "How do enrichment  │
│                   │   │                           │   │  groups work with    │
│                   │   │                           │   │  field mappings?"    │
└───────────────────┘   └───────────────────────────┘   └──────────────────────┘
     Static YAML             JSON index file               SimpleVectorStore
  apex-syntax-compact.yaml  example-index.json          apex-vector-store.json
```

### When to use each channel

| Need | Best Channel | Tool Call |
|---|---|---|
| Template for a doc type | Syntax | `getDocTypeTemplate("rule-config")` |
| Required fields for a doc type | Syntax | `getRequiredFields("scenario")` |
| SpEL expression syntax | Syntax | `getSpelRules()` |
| Examples with specific features | Tag-based | `searchExamples("rules,enrichments", "rule-config", 5)` |
| Corpus overview / statistics | Tag-based | `getCorpusStats()` |
| Conceptual understanding | Semantic | `semanticSearch("error recovery patterns in pipelines")` |
| Complex multi-feature patterns | Semantic | `semanticSearch("how to combine rule-groups with enrichment-groups")` |
| Documentation references | Semantic | `semanticSearch("SpEL expressions for date comparisons")` |

---

## Vector Store Ingestion Pipeline

The `ApexKnowledgeIngester` builds the vector store on first startup by scanning the `apex-rules-engine` project:

```
apex-rules-engine/
├── docs/                              ← ~22 markdown docs
│   ├── APEX_YAML_REFERENCE.md
│   ├── APEX_RULES_ENGINE_USER_GUIDE.md
│   ├── APEX_TECHNICAL_REFERENCE.md
│   ├── APEX_SPEL_GUIDE.md
│   └── ...
├── apex-compiler/                     ← Module-level docs
│   └── *.md
├── apex-playground/
│   ├── docs/                          ← Playground docs
│   └── examples/                      ← 71 YAML examples
│       ├── basic-rules/
│       ├── enrichments/
│       ├── rule-groups/
│       └── ...
├── apex-demo/src/test/resources/      ← 377 YAML test configs
│   ├── validation/
│   ├── enrichment/
│   ├── pipeline/
│   └── ...
├── apex-core/src/test/resources/      ← 141 YAML test configs
│   ├── rules/
│   ├── error-recovery/
│   └── ...
└── README.md
```

### Processing Pipeline

```
1. SCAN MARKDOWN FILES
   ├── Walk docs/, apex-compiler/, apex-playground/docs/
   ├── For each .md file:
   │   ├── Split by ## / ### heading boundaries
   │   ├── Each heading section → one Document chunk
   │   ├── Metadata: source path, fileName, section name, contentType="documentation"
   │   └── Large sections (>4000 chars) → split at paragraph boundaries
   └── Also process root README.md

2. SCAN YAML EXAMPLES
   ├── Walk apex-playground/examples/, apex-demo/.../resources/, apex-core/.../resources/
   ├── For each .yaml / .yml file:
   │   ├── Extract metadata from YAML content:
   │   │   ├── id, name, type, description (from `metadata:` block)
   │   │   └── purpose (from `# Purpose:` comments)
   │   ├── Detect features present (rules, enrichments, rule-groups, etc.)
   │   ├── Build searchable text = summary prefix + YAML content
   │   └── Metadata: source, fileName, contentType="yaml-example", category, docType, features
   └── Skip blank files

3. EMBED AND PERSIST
   ├── Batch documents (50 at a time) to avoid memory pressure
   ├── Call OpenAI text-embedding-3-small for each batch
   ├── Store embeddings in SimpleVectorStore (in-memory cosine similarity)
   └── Save to knowledge/apex-vector-store.json
```

### Startup Behavior

```
Application starts
    │
    ▼
ApexKnowledgeIngester.loadOrBuild()
    │
    ├── knowledge/apex-vector-store.json exists?
    │   ├── YES → Load from disk (fast, no API calls)
    │   └── NO  → Full scan + embed + save (one-time, uses OpenAI API)
    │
    ▼
SimpleVectorStore bean available
    │
    ├── Injected into ApexGenerationService (APEX client gets ApexSemanticSearch)
    └── Injected into chatClient (REPL client gets ApexSemanticSearch)
```

To force re-ingestion, delete `knowledge/apex-vector-store.json` and restart. Or call `ApexKnowledgeIngester.rebuild()` programmatically.

---

## Interaction with apex-rules-engine

The apex-ai-agent interacts with the apex-rules-engine project at **three levels**:

### Level 1: Compile-Time Library Dependencies

```xml
<!-- In apex-ai-agent/pom.xml -->
<dependency>
    <groupId>com.apex</groupId>
    <artifactId>apex-core</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.apex</groupId>
    <artifactId>apex-compiler</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

These give the apex-ai-agent access to:
- **APEX Compiler** — `ApexCompileTool` calls the real APEX compiler to validate YAML syntax, check for unknown keywords, verify SpEL expressions, and compile rule definitions into executable form
- **APEX Core Engine** — `ApexExecuteTool` calls the real engine to execute compiled rules against JSON fact data, returning per-rule results (triggered, message, severity, pass/fail)

This means validation is **not simulated** — when the LLM generates YAML, it's validated and executed by the same compiler and engine used in production.

### Level 2: Knowledge Index (Static)

The `knowledge/example-index.json` file is a pre-built index of **589 YAML files** from the apex-rules-engine project. It contains:
- File paths (relative to apex-rules-engine root)
- Document type (rule-config, scenario, component, etc.)
- Feature tags (rules, enrichments, rule-groups, etc.)
- Complexity rating (simple, moderate, complex)
- SpEL patterns found
- Line counts

This index is read by `ApexExampleRetrievalTool` to perform fast tag-based searches without touching the filesystem at runtime.

The `knowledge/apex-syntax-compact.yaml` file is a curated APEX syntax reference loaded by `ApexSyntaxTool`. It contains templates, required fields, valid keywords, and SpEL rules.

### Level 3: Knowledge Corpus (RAG Vector Store)

The `ApexKnowledgeIngester` **reads files directly from the apex-rules-engine project directory** at startup:

```
apex-ai-agent/                     apex-rules-engine/
    │                                │
    │  ← reads .md files from →      ├── docs/*.md
    │  ← reads .yaml files from →    ├── apex-playground/examples/**/*.yaml
    │  ← reads .yaml files from →    ├── apex-demo/src/test/resources/**/*.yaml
    │  ← reads .yaml files from →    └── apex-core/src/test/resources/**/*.yaml
    │
    └── knowledge/apex-vector-store.json  (persisted embeddings)
```

The default location assumes both projects are siblings:
```
parent-directory/
├── apex-ai-agent/          ← this project
└── apex-rules-engine/   ← the rules engine project
```

This is configurable via `apex.knowledge.project-root` or the `APEX_PROJECT_ROOT` environment variable.

### Data Flow Summary

```
                  ┌─────────────────────────────────────────────────┐
                  │               apex-rules-engine                 │
                  │                                                 │
                  │  apex-core     apex-compiler     docs/          │
                  │  (engine)      (compiler)        (markdown)     │
                  │     │              │              │              │
                  │     │              │              │  examples/   │
                  │     │              │              │  (YAML)      │
                  └─────┼──────────────┼──────────────┼──────────────┘
                        │              │              │
         ┌──────────────┘              │              └──────────────┐
         │                             │                             │
         ▼                             ▼                             ▼
┌─────────────────┐  ┌──────────────────────┐  ┌──────────────────────────┐
│ ApexExecuteTool  │  │ ApexCompileTool      │  │ ApexKnowledgeIngester    │
│                  │  │                      │  │                          │
│ Calls engine     │  │ Calls compiler       │  │ Reads files at startup,  │
│ at runtime to    │  │ at runtime to        │  │ chunks them, embeds via  │
│ execute rules    │  │ validate / compile   │  │ OpenAI API, persists to  │
│ against test     │  │ generated YAML       │  │ SimpleVectorStore        │
│ data             │  │                      │  │                          │
└────────┬─────────┘  └──────────┬───────────┘  └────────────┬─────────────┘
         │                       │                            │
         └───────────┬───────────┘                            │
                     │                                        │
                     ▼                                        ▼
              ┌──────────────┐                    ┌──────────────────────┐
              │   LLM Agent  │◄───────────────────│ ApexKnowledgeSearch  │
              │   (GPT-4o)   │   semantic search  │ Tool (vector store)  │
              │              │                    │                      │
              │  Generates   │                    │ + ApexExampleRetrieval│
              │  YAML, calls │                    │   Tool (tag search)  │
              │  tools to    │                    │ + ApexSyntaxTool     │
              │  validate    │                    │   (schema reference) │
              └──────────────┘                    └──────────────────────┘
```

---

## Web UI and REST API

### REST API Endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/apex/generate` | Submit a generation request. Returns a job ID for polling. |
| `GET` | `/api/apex/status/{jobId}` | Poll for job status and results. |

**POST body:**
```json
{
  "requirements": "Create rules for credit scoring...",
  "dataStructure": "{\"score\": 750, \"income\": 50000}",
  "hints": "use rule-groups, include enrichments"
}
```

**Status response:**
```json
{
  "jobId": "apex-1234567890-abcdef",
  "status": "completed",
  "result": {
    "success": true,
    "files": [{"relPath": "rules/credit-scoring.yaml", "content": "..."}],
    "validationReport": {
      "lexicalValid": true,
      "compilationSuccess": true,
      "executionSuccess": true,
      "expectationsPass": true
    },
    "ruleResults": [
      {"ruleId": "min-score", "ruleName": "Minimum Score", "triggered": true, "message": "...", "severity": "INFO"}
    ],
    "summary": "Generated 1 rule-config with 3 rules..."
  }
}
```

### Web UI

The `index.html` page provides:
- A form to enter requirements, data structure, and hints
- Real-time job status polling
- Expandable validation stage panels (lexical, compilation, execution, expectations)
- Per-rule execution results table with ruleId, ruleName, triggered status, message, severity
- Generated YAML display with syntax highlighting

---

## Configuration Reference

### application.yaml

```yaml
spring:
  ai:
    openai-sdk:
      api-key: ${OPENAI_API_KEY}         # Required — OpenAI API key
      chat:
        options:
          model: gpt-4o                   # Chat model for generation
      embedding:
        options:
          model: text-embedding-3-small   # Embedding model for vector store
      base-url: https://api.openai.com/v1

# APEX RAG knowledge base
apex:
  knowledge:
    enabled: true                                              # Set false to skip vector store
    project-root: ${APEX_PROJECT_ROOT:../apex-rules-engine}    # Path to apex-rules-engine
    vector-store-path: knowledge/apex-vector-store.json        # Where to persist embeddings
```

### Key Properties

| Property | Default | Description |
|---|---|---|
| `OPENAI_API_KEY` | (required) | OpenAI API key for chat and embeddings |
| `apex.knowledge.enabled` | `true` | Enable/disable the vector store. Set `false` for tests. |
| `apex.knowledge.project-root` | `../apex-rules-engine` | Path to the apex-rules-engine project root |
| `apex.knowledge.vector-store-path` | `knowledge/apex-vector-store.json` | Persistence file for vector embeddings |
| `app.repl.enabled` | `true` | Enable/disable the CLI REPL loop |

---

## Building and Running

### Prerequisites

1. Java 23+
2. The `apex-rules-engine` project built and installed locally:
   ```bash
   cd ../apex-rules-engine
   mvn clean install -DskipTests
   ```
3. An OpenAI API key

### Build

```bash
cd apex-ai-agent
./mvnw clean package
```

### Run (CLI REPL)

```bash
export OPENAI_API_KEY=sk-your-key
./mvnw spring-boot:run
```

### Run (Web UI only)

```bash
export OPENAI_API_KEY=sk-your-key
./mvnw spring-boot:run -Dspring-boot.run.arguments=--app.repl.enabled=false
```

Then open http://localhost:8080 in a browser.

### First Startup (Vector Store Build)

On the **first run**, the `ApexKnowledgeIngester` will:
1. Scan all `.md` and `.yaml` files in the `apex-rules-engine` project
2. Chunk markdown files by headings, process YAML files with metadata extraction
3. Call the OpenAI embedding API to generate vectors for each document chunk
4. Save the vector store to `knowledge/apex-vector-store.json`

This takes a few minutes and makes OpenAI API calls. Subsequent startups load from disk instantly.

### Rebuilding the Vector Store

If the apex-rules-engine documentation or examples change:
1. Delete `knowledge/apex-vector-store.json`
2. Restart the application
3. The vector store will be rebuilt automatically

---

## Testing Strategy

### Test Categories

| Category | Test Class | Count | Description |
|---|---|---|---|
| Spring context | `ChatClientConfigTests` | ~12 | Verifies bean wiring, tool sets, advisor config, builder isolation |
| Smoke test | `ApplicationTests` | ~2 | Real LLM call (requires API key, skipped in CI) |
| REPL | `ReplTests` | ~5 | REPL loop control flow with mocked ChatClient |
| Compile tool | `ApexCompileToolTest` | ~20 | Lexical validation, compilation, error classification |
| Execute tool | `ApexExecuteToolTest` | ~25 | Rule execution, batch execution, result collection |
| Expectation tool | `ApexExpectationToolTest` | ~15 | Outcome assertion, pass/fail logic |
| Syntax tool | `ApexSyntaxToolTest` | ~10 | Template retrieval, required fields |
| Example search | `ApexExampleRetrievalToolTest` | ~12 | Tag-based search, scoring, corpus stats |
| Knowledge ingester | `ApexKnowledgeIngesterTest` | 13 | Markdown chunking, YAML processing, feature detection |
| Knowledge search | `ApexKnowledgeSearchToolTest` | 9 | Semantic search formatting, edge cases, builder |
| Orchestration | `ApexGenerationServiceTest` | ~15 | Pipeline orchestration, retry logic |
| Output packager | `OutputPackagerTest` | ~20 | LLM response parsing, file extraction |
| Generation result | `GenerationResultTest` | ~5 | Result record construction |
| REST controller | `ApexGenerationControllerTest` | ~13 | REST API, async jobs, error handling |
| Engine smoke | `ApexEngineSmokeTest` | ~5 | Direct apex-core integration |
| Compiler smoke | `ApexCompilerSmokeTest` | ~5 | Direct apex-compiler integration |

**Total: 209 tests**, all passing.

### Test Isolation

- Tests that use `@SpringBootTest` set `apex.knowledge.enabled=false` to skip vector store creation (no OpenAI API call needed)
- The `ApplicationTests` smoke test is gated by `@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY")` — it only runs when credentials are available
- Knowledge ingester and search tool tests use `@TempDir` and mock `EmbeddingModel`/`VectorStore` — no external dependencies

### Running Tests

```bash
# Run all tests (no API key needed for unit tests)
./mvnw test

# Run specific test class
./mvnw test -Dtest=ApexKnowledgeIngesterTest

# Run with real LLM (requires API key)
OPENAI_API_KEY=sk-your-key ./mvnw test
```

---

## Appendix: Complete Tool Call Trace Example

Here's what happens when a user types "Create validation rules for a loan application":

```
1. User → REPL ChatClient
   "Create validation rules for a loan application"

2. LLM → Tool Call: GenerateApexRules
   requirements="Create validation rules for a loan application"
   dataStructure="{...}"

3. ApexGenerateCommand → ApexGenerationService.generate()

4. ApexGenerationService → APEX ChatClient (specialist)
   System prompt: "You are an APEX Rules Configuration Generator..."

5. LLM (specialist) → Tool Call: ApexGetDocTypeTemplate("rule-config")
   ← Returns YAML template

6. LLM → Tool Call: ApexGetRequiredFields("rule-config")
   ← Returns required fields list

7. LLM → Tool Call: ApexSearchExamples("rules,enrichments", "rule-config", 5)
   ← Returns top-5 matching examples by tag overlap

8. LLM → Tool Call: ApexSemanticSearch("loan application validation rules with credit check")
   ← Returns relevant docs and YAML examples by vector similarity

9. LLM → Tool Call: ApexGetSpelRules()
   ← Returns SpEL syntax reference

10. LLM authors YAML based on templates, examples, and documentation

11. LLM → Tool Call: ApexValidateAndCompile(yamlContent)
    ← Returns lexical + compilation result

12. If errors: LLM fixes YAML and re-validates (loop)

13. LLM → Tool Call: ApexExecute(yamlContent, testJson)
    ← Returns per-rule execution results

14. LLM → Tool Call: ApexAssertExpectations(actualResult, expectations)
    ← Returns pass/fail for each business assertion

15. LLM formats final response with labeled sections

16. OutputPackager extracts YAML, test data, and validation report
    Drains RuleResults from ApexExecuteTool's static collector

17. GenerationResult returned to caller with:
    - Generated YAML file(s)
    - Validation report (lexical, compile, execute, expectations)
    - Per-rule results (ruleId, ruleName, triggered, message, severity)
    - Human-readable summary
```
