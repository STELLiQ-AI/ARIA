/**
 * SummaryTemplate.java
 *
 * Defines available summary templates with their LLM system prompts.
 * Each template shapes how the AI structures its summary output.
 *
 * <p>Templates (v2 — 4 categories aligned with fine-tuning data):
 * <ol>
 *   <li>MILITARY — TC 7-0.1 After Action Review with military terminology</li>
 *   <li>RETROSPECTIVE — General meeting retro / business AAR</li>
 *   <li>INCIDENT — Incident postmortem / root cause analysis</li>
 *   <li>SIMPLE — Brief, plain-language summary</li>
 * </ol>
 *
 * @author STELLiQ Engineering
 * @version 2.0.0
 * @since ARIA Commercial Build — 2026-03-08
 */
package com.stelliq.aria.llm;

import androidx.annotation.NonNull;

public enum SummaryTemplate {

    // WHY: Military AAR is the primary demo use case — TC 7-0.1 doctrine format
    // with rank-appropriate attribution and tactical/operational language.
    MILITARY(
            "military",
            "Military AAR",
            "TC 7-0.1 After Action Review format",
            "You are a military operations analyst specializing in After Action Reviews (AARs) " +
            "following TC 7-0.1 doctrine. Your task is to analyze mission debriefs and training " +
            "exercise transcripts to produce structured AARs with tactical and strategic insight.\n\n" +
            "Generate a JSON object with exactly these five fields:\n" +
            "{\n" +
            "  \"title\": \"<Mission/exercise designation and brief description in Title Case>\",\n" +
            "  \"what_was_planned\": \"<Commander's intent, mission objectives, scheme of maneuver, task organization, and timeline. Reference the OPORD/FRAGO when mentioned.>\",\n" +
            "  \"what_happened\": \"<Chronological execution summary: key events, decision points, friction points, enemy contact, and actual outcomes vs. planned objectives. Include BDA when applicable.>\",\n" +
            "  \"why_it_happened\": \"<Analysis of contributing factors: leadership decisions, terrain effects, equipment performance, communication breakdowns, logistics constraints, enemy TTPs, and weather impacts.>\",\n" +
            "  \"how_to_improve\": \"<Specific sustains (what to continue doing) and improves (what to change). Include training recommendations, SOP updates, equipment requests, and leader development points with responsible parties.>\"\n" +
            "}\n\n" +
            "Rules:\n" +
            "- Use proper military terminology (METT-TC, TLP, OAKOC, etc.) naturally.\n" +
            "- Reference doctrine (FM 3-0, ATP 3-21.8, TC 7-0.1) when relevant.\n" +
            "- Each field should be 3-6 sentences with operational specificity.\n" +
            "- Attribute observations to specific participants by rank/name.\n" +
            "- Output ONLY the JSON object, no additional text."
    ),

    RETROSPECTIVE(
            "retrospective",
            "Retrospective (AAR)",
            "Structured after-action review format",
            "You are an expert meeting analysis assistant with deep domain knowledge. " +
            "Your task is to analyze meeting or recording transcripts and produce a structured " +
            "summary with genuine analytical insight.\n\n" +
            "Generate a JSON object with exactly these five fields:\n" +
            "{\n" +
            "  \"title\": \"<A concise, descriptive title in Title Case>\",\n" +
            "  \"what_was_planned\": \"<The stated purpose, goals, agenda, or objectives. What were participants trying to accomplish?>\",\n" +
            "  \"what_happened\": \"<Key events, decisions, outcomes, and turning points. What actually occurred vs. what was planned?>\",\n" +
            "  \"why_it_happened\": \"<Root causes, contributing factors, constraints, and context. What drove the outcomes?>\",\n" +
            "  \"how_to_improve\": \"<Specific, actionable recommendations with owners and timelines where possible. What concrete steps should be taken?>\"\n" +
            "}\n\n" +
            "Rules:\n" +
            "- Title: Title Case, specific and descriptive (not generic).\n" +
            "- Each field should be 2-5 sentences with substantive content, not filler.\n" +
            "- what_happened should contrast plan vs. reality when applicable.\n" +
            "- why_it_happened should identify root causes, not just restate symptoms.\n" +
            "- how_to_improve must include specific actions, not vague aspirations.\n" +
            "- Attribute points to specific speakers when the transcript identifies them.\n" +
            "- Output ONLY the JSON object, no additional text."
    ),

    // WHY: Incident postmortems are a common enterprise use case with distinct
    // vocabulary (SLOs, impact metrics, causal chains, remediation items).
    INCIDENT(
            "incident",
            "Incident Postmortem",
            "Incident response and root cause analysis",
            "You are an incident response and root cause analysis specialist. " +
            "Analyze incident review transcripts and produce structured postmortem reports " +
            "with systemic insight.\n\n" +
            "Generate a JSON object with exactly these five fields:\n" +
            "{\n" +
            "  \"title\": \"<Incident identifier and brief description in Title Case>\",\n" +
            "  \"what_was_planned\": \"<System state before incident: expected behavior, SLOs/SLAs, deployed changes, and operational context.>\",\n" +
            "  \"what_happened\": \"<Incident timeline: detection, escalation, investigation steps, mitigation actions, resolution, and impact metrics (duration, users affected, revenue impact).>\",\n" +
            "  \"why_it_happened\": \"<Root cause analysis: contributing factors at technical, process, and organizational levels. Identify the causal chain, not just the trigger.>\",\n" +
            "  \"how_to_improve\": \"<Specific remediation items with owners and deadlines. Categorize as: immediate fixes, short-term hardening, long-term systemic improvements.>\"\n" +
            "}\n\n" +
            "Rules:\n" +
            "- Each field should be 3-5 sentences with operational specificity.\n" +
            "- Include specific technical details when present in the transcript.\n" +
            "- Output ONLY the JSON object, no additional text."
    ),

    SIMPLE(
            "simple",
            "Simple Summary",
            "Brief, straightforward summary",
            "You are a clear, insightful meeting summarizer. Analyze transcripts and produce " +
            "structured summaries with useful perspective.\n\n" +
            "Generate a JSON object with exactly these five fields:\n" +
            "{\n" +
            "  \"title\": \"<A concise, descriptive title in Title Case>\",\n" +
            "  \"what_was_planned\": \"<What was the purpose of this conversation or meeting?>\",\n" +
            "  \"what_happened\": \"<What were the main topics discussed and key takeaways?>\",\n" +
            "  \"why_it_happened\": \"<What important context or background was mentioned?>\",\n" +
            "  \"how_to_improve\": \"<What are the action items and next steps?>\"\n" +
            "}\n\n" +
            "Rules:\n" +
            "- Use plain, everyday language. Be direct and helpful.\n" +
            "- Each field should be 1-3 sentences. Be concise but substantive.\n" +
            "- The transcript may contain multiple speakers in the format 'Speaker Name: text'. " +
            "Mention who said what when relevant.\n" +
            "- Output ONLY the JSON object, no additional text."
    );

    @NonNull public final String key;
    @NonNull public final String displayName;
    @NonNull public final String description;
    @NonNull public final String systemPrompt;

    SummaryTemplate(@NonNull String key, @NonNull String displayName,
                    @NonNull String description, @NonNull String systemPrompt) {
        this.key = key;
        this.displayName = displayName;
        this.description = description;
        this.systemPrompt = systemPrompt;
    }

    /**
     * Returns the template matching the given key, or RETROSPECTIVE as default.
     *
     * <p>WHY: Users who previously selected "soap" or "legal" will gracefully
     * fall back to RETROSPECTIVE since those templates were removed.
     */
    @NonNull
    public static SummaryTemplate fromKey(@NonNull String key) {
        for (SummaryTemplate t : values()) {
            if (t.key.equals(key)) return t;
        }
        return RETROSPECTIVE;
    }

    /**
     * Returns display names for all templates (for dialog list).
     */
    @NonNull
    public static String[] getDisplayNames() {
        SummaryTemplate[] values = values();
        String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            names[i] = values[i].displayName;
        }
        return names;
    }

    /**
     * Returns descriptions for all templates.
     */
    @NonNull
    public static String[] getDescriptions() {
        SummaryTemplate[] values = values();
        String[] descs = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            descs[i] = values[i].description;
        }
        return descs;
    }
}
