package com.example.impactagent.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import com.example.impactagent.tools.CodeTools;

/**
 * Two modes:
 *  - analyze(): the agentic, tool-calling investigator (kept for reference / future use).
 *  - narrate(): grounded narration. It is GIVEN the verified call-graph facts and only explains
 *    them, so the written report can never contradict the structured cards. This is what the UI uses.
 */
@Service
public class ImpactAgentService {

    private static final String INVESTIGATION_PROMPT = """
            You are a change-impact analyst for a Java codebase. Use the tools to read the real call
            graph - never guess who calls what. Find the method, get its impact set, read the key
            affected files, then report risk, direct breakage, behavioral risk, and recommended checks.
            """;

    private static final String NARRATION_PROMPT = """
            You are a code change-impact analyst. You will be GIVEN the verified results of a static
            call-graph analysis. These numbers are GROUND TRUTH - do not recompute, re-derive, or
            second-guess them, and never state a different risk level or count than the ones provided.
            Write a concise, professional narrative that EXPLAINS the given facts to a developer in
            plain language, then end with 3-5 concrete checks to run before shipping. Plain text, no
            markdown headings.
            """;

    private final ChatClient toolClient;
    private final ChatClient narrateClient;

    public ImpactAgentService(ChatModel chatModel, CodeTools codeTools) {
        this.toolClient = ChatClient.builder(chatModel)
                .defaultSystem(INVESTIGATION_PROMPT)
                .defaultTools(codeTools)
                .build();
        this.narrateClient = ChatClient.builder(chatModel)
                .defaultSystem(NARRATION_PROMPT)
                .build();
    }

    /** Agentic, tool-driven investigation (not used by the UI; kept as the autonomous mode). */
    public String analyze(String methodNameOrId) {
        return toolClient.prompt()
                .user("Analyze the downstream impact of changing this method: " + methodNameOrId)
                .call()
                .content();
    }

    /** Grounded narration: explains the verified facts, guaranteed consistent with the cards. */
    public String narrate(String method, String risk, String summary,
                          int methodsAffected, int filesTouched, int maxDepth, int directCallers,
                          String directBreakage, String behavioralRisk) {
        String user = """
                Method under change: %s
                Verified risk level: %s
                Verified one-line summary: %s
                Verified blast radius: %d methods affected, %d files touched, max depth %d hops, %d direct callers.

                Direct breakage (these break on a signature change):
                %s

                Behavioral risk (these depend on current behavior):
                %s

                Write the analysis now. Keep the risk level and every number EXACTLY as given above.
                """.formatted(method, risk, summary, methodsAffected, filesTouched, maxDepth, directCallers,
                directBreakage.isBlank() ? "(none)" : directBreakage,
                behavioralRisk.isBlank() ? "(none)" : behavioralRisk);

        return narrateClient.prompt().user(user).call().content();
    }
}