package dev.mars.codingagent.orchestration;

import dev.mars.codingagent.tools.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.VectorStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/**
 * Orchestrates the APEX YAML generation pipeline.
 * <p>
 * Runs a single-agent sequential loop:
 * <ol>
 *   <li>Build a user prompt from the requirements + data structure</li>
 *   <li>Send to the LLM with APEX-specific system prompt and tools</li>
 *   <li>The LLM autonomously plans, retrieves, authors, validates, and fixes</li>
 *   <li>Parse and package the final output</li>
 * </ol>
 * <p>
 * The LLM has access to all APEX tools (syntax, examples, compile, execute, expectations)
 * and is instructed to follow a strict workflow with up to 3 retries per artifact.
 */
public class ApexGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ApexGenerationService.class);
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final String PROMPT_RESOURCE = "/prompts/apex-generation-system.txt";

    private final ChatClient apexChatClient;
    private final OutputPackager outputPackager;
    private final int maxAttempts;

    private ApexGenerationService(ChatClient apexChatClient, OutputPackager outputPackager, int maxAttempts) {
        this.apexChatClient = apexChatClient;
        this.outputPackager = outputPackager;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Returns the internal ChatClient — exposed for integration testing only.
     * Verifies that the APEX client is independently configured from the REPL client.
     */
    public ChatClient getApexChatClient() {
        return apexChatClient;
    }

    /**
     * Generate APEX YAML configuration from business requirements.
     *
     * @param request the generation request containing requirements and data structure
     * @return the generation result with files, validation report, and summary
     */
    public GenerationResult generate(GenerationRequest request) {
        log.info("Starting APEX generation for request: {}", request.requestId());
        log.debug("Request details — requirements length: {}, dataStructure length: {}, hints: {}",
                request.requirements().length(),
                request.dataStructure() != null ? request.dataStructure().length() : 0,
                request.hints());

        String userPrompt = buildUserPrompt(request);
        log.debug("Built user prompt ({} chars): {}...", userPrompt.length(),
                userPrompt.substring(0, Math.min(300, userPrompt.length())));
        String response = null;
        int attempt = 0;

        // The LLM handles its own internal retry loop via tool calls.
        // This outer loop is a safety net: if the LLM response doesn't contain
        // valid output, we can re-prompt with additional guidance.
        while (attempt < maxAttempts) {
            attempt++;
            log.info("Generation attempt {}/{} for request {}", attempt, maxAttempts, request.requestId());

            try {
                String promptToSend = attempt == 1 ? userPrompt
                        : buildRetryPrompt(userPrompt, response, attempt);
                log.debug("Sending prompt ({} chars) to LLM on attempt {}",
                        promptToSend.length(), attempt);

                response = apexChatClient.prompt(promptToSend)
                        .toolContext(Map.of(
                                "workingDir", System.getProperty("user.dir"),
                                "requestId", request.requestId()
                        ))
                        .call()
                        .content();

                if (response == null || response.isBlank()) {
                    log.warn("Empty response on attempt {}", attempt);
                    continue;
                }

                log.debug("Received LLM response ({} chars). Preview: {}...",
                        response.length(),
                        response.substring(0, Math.min(500, response.length())));

                // Try to package the output
                GenerationResult result = outputPackager.packageOutput(
                        request.requestId(), response, attempt);

                if (result.success()) {
                    log.info("Generation succeeded on attempt {} for request {}",
                            attempt, request.requestId());
                    log.debug("Result: {} files generated, validation report: lexical={}, compile={}, exec={}, expectations={}",
                            result.files().size(),
                            result.validationReport().lexicalValid(),
                            result.validationReport().compilationSuccess(),
                            result.validationReport().executionSuccess(),
                            result.validationReport().expectationsPass());
                    return result;
                }

                // If packaging found content but validation didn't fully pass,
                // still return the result — the caller can inspect the report
                if (!result.files().isEmpty()) {
                    log.info("Generation completed with partial success on attempt {} for request {}",
                            attempt, request.requestId());
                    log.debug("Partial result: {} files, validation: lexical={}, compile={}, exec={}, expectations={}",
                            result.files().size(),
                            result.validationReport().lexicalValid(),
                            result.validationReport().compilationSuccess(),
                            result.validationReport().executionSuccess(),
                            result.validationReport().expectationsPass());
                    return result;
                }

                log.warn("No valid output found on attempt {}. Retrying...", attempt);

            } catch (IOException e) {
                log.error("IO error during generation attempt {}: {}", attempt, e.getMessage());
                response = "Error: " + e.getMessage();
            } catch (Exception e) {
                log.error("Unexpected error during generation attempt {}: {}", attempt, e.getMessage(), e);
                response = "Error: " + e.getMessage();
            }
        }

        log.error("All {} attempts exhausted for request {}", maxAttempts, request.requestId());
        return GenerationResult.failed(request.requestId(), maxAttempts,
                "All " + maxAttempts + " generation attempts exhausted. Last response: "
                        + (response != null ? response.substring(0, Math.min(500, response.length())) : "null"));
    }

    // ---- Prompt Building ----

    private String buildUserPrompt(GenerationRequest request) {
        log.debug("Building user prompt for request {}", request.requestId());
        StringBuilder sb = new StringBuilder();
        sb.append("## Business Requirements\n\n");
        sb.append(request.requirements()).append("\n\n");

        if (request.dataStructure() != null && !request.dataStructure().isBlank()) {
            sb.append("## Data Structure\n\n");
            sb.append(request.dataStructure()).append("\n\n");
        }

        if (request.hints() != null && !request.hints().isEmpty()) {
            sb.append("## Additional Hints\n\n");
            for (String hint : request.hints()) {
                sb.append("- ").append(hint).append("\n");
            }
            sb.append("\n");
        }

        sb.append("Generate the APEX YAML configuration following your workflow. ");
        sb.append("Validate it completely before returning your final answer.");

        return sb.toString();
    }

    private String buildRetryPrompt(String originalPrompt, String previousResponse, int attempt) {
        log.debug("Building retry prompt for attempt {}. Previous response length: {}",
                attempt, previousResponse != null ? previousResponse.length() : 0);
        return """
                PREVIOUS ATTEMPT FAILED. This is attempt %d.
                
                Previous response did not contain valid, parseable APEX YAML output.
                Please follow the output format exactly as specified in your instructions.
                Make sure to include the === GENERATED YAML === section with the complete YAML.
                
                Original request:
                %s
                """.formatted(attempt, originalPrompt);
    }

    // ---- System Prompt Loading ----

    static String loadSystemPrompt(String requestId) {
        log.debug("Loading system prompt template for requestId={}", requestId);
        String template = loadPromptTemplate();
        log.debug("System prompt template loaded ({} chars)", template.length());
        return template
                .replace("{{workingDir}}", System.getProperty("user.dir"))
                .replace("{{osName}}", System.getProperty("os.name"))
                .replace("{{requestId}}", requestId != null ? requestId : "unknown");
    }

    private static String loadPromptTemplate() {
        try (InputStream is = ApexGenerationService.class.getResourceAsStream(PROMPT_RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException("System prompt resource not found: " + PROMPT_RESOURCE);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load system prompt", e);
        }
    }

    // ---- Builder ----

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private ChatModel chatModel;
        private VectorStore vectorStore;
        private Path outputDir = Path.of("generated", "apex");
        private int maxAttempts = DEFAULT_MAX_ATTEMPTS;

        private Builder() {}

        /**
         * Set the ChatModel to use for the APEX-specific ChatClient.
         * A fresh ChatClient is built from scratch (not cloned from the auto-configured
         * builder) to avoid inheriting auto-configured advisors such as
         * MessageChatMemoryAdvisor, which can leak tool messages between calls.
         */
        public Builder chatModel(ChatModel chatModel) {
            this.chatModel = chatModel;
            return this;
        }

        /**
         * Set the VectorStore for semantic search over APEX documentation and examples.
         * When provided, an {@code ApexKnowledgeSearchTool} is added to the tool set.
         */
        public Builder vectorStore(VectorStore vectorStore) {
            this.vectorStore = vectorStore;
            return this;
        }

        public Builder outputDir(Path outputDir) {
            this.outputDir = outputDir;
            return this;
        }

        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        public ApexGenerationService build() {
            if (chatModel == null) {
                throw new IllegalStateException("chatModel is required");
            }

            // Build a completely fresh ChatClient — NOT cloned from the auto-configured
            // builder. Cloning inherits auto-configured advisors (e.g. ChatMemory)
            // that cause 'tool messages without tool_calls' errors from OpenAI.
            //
            // NOTE: conversationHistoryEnabled must be true (the default) so that
            // during tool-call loops, the full conversation [system, user, assistant(tool_calls),
            // tool(result)] is sent to the model. When false, ToolCallAdvisor strips
            // the context to [system, lastToolResult], violating OpenAI's constraint
            // that 'tool' messages must follow an assistant message with 'tool_calls'.

            // Build tool list — semantic search is optional
            var tools = new java.util.ArrayList<Object>();
            tools.add(ApexCompileTool.builder().build());
            tools.add(ApexExecuteTool.builder().build());
            tools.add(ApexExpectationTool.builder().build());
            tools.add(ApexSyntaxTool.builder().build());
            tools.add(ApexExampleRetrievalTool.builder().build());
            if (vectorStore != null) {
                tools.add(ApexKnowledgeSearchTool.builder().vectorStore(vectorStore).build());
                log.info("ApexKnowledgeSearchTool enabled with vector store");
            }

            ChatClient apexClient = ChatClient.builder(chatModel)
                    .defaultSystem(loadSystemPrompt(null))
                    .defaultTools(tools.toArray())
                    .defaultAdvisors(
                            ToolCallAdvisor.builder().build()
                    )
                    .build();

            OutputPackager packager = new OutputPackager(outputDir);
            return new ApexGenerationService(apexClient, packager, maxAttempts);
        }
    }
}
