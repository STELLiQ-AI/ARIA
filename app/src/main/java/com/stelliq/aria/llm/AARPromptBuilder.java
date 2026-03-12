/**
 * AARPromptBuilder.java
 *
 * Builds prompts for Llama 3.1 8B Instruct to generate structured AAR summaries.
 * Constructs Llama 3.1 Instruct-formatted prompts with system instruction and transcript.
 *
 * <p>Responsibility:
 * <ul>
 *   <li>Build Llama 3.1 Instruct-formatted prompts</li>
 *   <li>Include four-step AAR structure in system prompt</li>
 *   <li>Inject transcript into user message</li>
 *   <li>Specify JSON output format</li>
 *   <li>This class does NOT run inference — that's LlamaEngine's job</li>
 * </ul>
 *
 * <p>Architecture Position:
 * LLM layer input builder. Called by ARIASessionService before LlamaEngine.complete().
 *
 * <p>Thread Safety:
 * Stateless builder. Thread-safe.
 *
 * <p>Prompt Format (Llama 3.2 Instruct):
 * <pre>
 * &lt;|start_header_id|&gt;system&lt;|end_header_id|&gt;
 *
 * You are an AAR assistant...&lt;|eot_id|&gt;
 * &lt;|start_header_id|&gt;user&lt;|end_header_id|&gt;
 *
 * Summarize this meeting transcript:
 * {transcript}&lt;|eot_id|&gt;
 * &lt;|start_header_id|&gt;assistant&lt;|end_header_id|&gt;
 *
 * </pre>
 *
 * <p>IMPORTANT: parse_special must be true in llama_tokenize() so that control
 * tokens like &lt;|start_header_id|&gt; are recognized as special token IDs,
 * not tokenized as literal text characters.
 *
 * @author STELLiQ Engineering
 * @version 0.2.0
 * @since ARIA Commercial Build — 2026-03-08
 */
package com.stelliq.aria.llm;

import androidx.annotation.NonNull;

import com.stelliq.aria.util.Constants;

public class AARPromptBuilder {

    // WHY: Llama 3.2 Instruct control tokens
    // These are recognized as special token IDs by the tokenizer when parse_special=true.
    // The double newline after end_header_id is required by Llama 3.2's training format.
    private static final String SYSTEM_START = "<|start_header_id|>system<|end_header_id|>\n\n";
    private static final String USER_START = "<|start_header_id|>user<|end_header_id|>\n\n";
    // WHY: Prefix with "{\n" to force the model to generate JSON immediately.
    // Without this, the 1B model may output prose ("Here is the summary...") on long
    // prompts where instruction-following degrades. The "{" is part of the prompt —
    // the caller must prepend "{" to the raw output before parsing.
    private static final String ASSISTANT_START = "<|start_header_id|>assistant<|end_header_id|>\n\n{\n";
    private static final String END_TOKEN = "<|eot_id|>";

    // WHY: Default system prompt — used when no template is specified
    // Template-specific prompts are defined in SummaryTemplate enum
    private static final String SYSTEM_PROMPT = SummaryTemplate.RETROSPECTIVE.systemPrompt;

    // WHY: User message template with transcript placeholder
    private static final String USER_PROMPT_TEMPLATE =
            "Summarize this meeting transcript:\n\n%s";

    /**
     * Private constructor — use static build() method.
     */
    private AARPromptBuilder() {
    }

    /**
     * Builds a complete prompt for Llama 3.2 using the default template.
     *
     * @param transcript The debrief transcript to summarize
     * @return Complete ChatML-formatted prompt
     */
    @NonNull
    public static String build(@NonNull String transcript) {
        return build(transcript, SummaryTemplate.RETROSPECTIVE);
    }

    /**
     * Builds a complete prompt for Llama 3.2 using a specific summary template.
     *
     * @param transcript The transcript to summarize
     * @param template   The summary template to use
     * @return Complete ChatML-formatted prompt
     */
    @NonNull
    public static String build(@NonNull String transcript, @NonNull SummaryTemplate template) {
        StringBuilder prompt = new StringBuilder();

        // System message from template
        prompt.append(SYSTEM_START);
        prompt.append(template.systemPrompt);
        prompt.append(END_TOKEN);

        // User message with transcript
        prompt.append(USER_START);
        prompt.append(String.format(USER_PROMPT_TEMPLATE, transcript));
        prompt.append(END_TOKEN);

        // Assistant start (model completes from here)
        prompt.append(ASSISTANT_START);

        return prompt.toString();
    }

    /**
     * Builds a prompt with custom system instructions.
     *
     * <p>PHASE 2: Support customizable prompts.
     *
     * @param transcript       The debrief transcript to summarize
     * @param customSystemPrompt Custom system instructions
     * @return Complete ChatML-formatted prompt
     */
    @NonNull
    public static String buildCustom(@NonNull String transcript, @NonNull String customSystemPrompt) {
        StringBuilder prompt = new StringBuilder();

        prompt.append(SYSTEM_START);
        prompt.append(customSystemPrompt);
        prompt.append(END_TOKEN);

        prompt.append(USER_START);
        prompt.append(String.format(USER_PROMPT_TEMPLATE, transcript));
        prompt.append(END_TOKEN);

        prompt.append(ASSISTANT_START);

        return prompt.toString();
    }

    /**
     * Estimates the token count for a prompt.
     *
     * <p>WHY: Rough estimate for context window management.
     * Phi-3 tokenizer averages ~4 chars per token for English.
     *
     * @param prompt The full prompt string
     * @return Estimated token count
     */
    public static int estimateTokens(@NonNull String prompt) {
        // WHY: Simple heuristic — actual tokenization varies
        return prompt.length() / 4;
    }

    /**
     * Checks if transcript fits within context window.
     *
     * @param transcript The transcript to check
     * @return True if transcript fits with system prompt and output buffer
     */
    public static boolean fitsInContext(@NonNull String transcript) {
        String fullPrompt = build(transcript);
        int estimatedTokens = estimateTokens(fullPrompt);

        // WHY: Reserve tokens for output (max 512)
        int maxInputTokens = Constants.LLM_CONTEXT_SIZE - Constants.LLM_MAX_OUTPUT_TOKENS;

        return estimatedTokens <= maxInputTokens;
    }

    /**
     * Truncates transcript to fit context window if needed.
     *
     * @param transcript The transcript to truncate
     * @return Truncated transcript that fits in context
     */
    @NonNull
    public static String truncateToFit(@NonNull String transcript) {
        if (fitsInContext(transcript)) {
            return transcript;
        }

        // WHY: Calculate max transcript length
        // Leave room for system prompt (~600 tokens) + output buffer (512 tokens)
        int systemPromptTokens = estimateTokens(SYSTEM_PROMPT) + 50;  // + template overhead
        int maxTranscriptTokens = Constants.LLM_CONTEXT_SIZE
                - Constants.LLM_MAX_OUTPUT_TOKENS
                - systemPromptTokens;
        int maxTranscriptChars = maxTranscriptTokens * 4;

        // WHY: Truncate with indicator
        if (transcript.length() > maxTranscriptChars) {
            return transcript.substring(0, maxTranscriptChars - 50)
                    + "\n\n[Transcript truncated due to length...]";
        }

        return transcript;
    }

    /**
     * Returns the system prompt for inspection.
     */
    @NonNull
    public static String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }
}
