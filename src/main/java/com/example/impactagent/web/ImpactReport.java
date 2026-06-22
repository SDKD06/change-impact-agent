package com.example.impactagent.web;

import java.util.List;

/**
 * The structured impact report returned by POST /api/impact.
 * Everything here except agentReport is computed deterministically from the real call graph -
 * no guessing, no placeholder data.
 */
public record ImpactReport(
        String query,
        String resolvedId,
        boolean found,
        String riskLevel,     // "Low" | "Medium" | "High" | null
        String summary,
        Stats stats,
        List<AffectedMethod> directBreakage,   // hop 1 - break on a signature change
        List<AffectedMethod> behavioralRisk,   // hop 2+ - rely on current behavior
        Graph graph,
        String agentReport
) {
    public record Stats(int methodsAffected, int filesTouched, int maxDepth, int directCallers) {}

    public record AffectedMethod(String name, String file, int line, int hop) {}

    public record Graph(List<Node> nodes, List<Edge> edges) {}
    public record Node(String id, String label, int hop) {}
    public record Edge(String from, String to) {}
}
