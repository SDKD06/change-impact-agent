package com.example.impactagent.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.impactagent.agent.ImpactAgentService;
import com.example.impactagent.graph.CallGraphService;
import com.example.impactagent.graph.CallGraphService.ImpactNode;
import com.example.impactagent.graph.MethodRef;

/**
 * Turns the raw call graph into the structured ImpactReport the UI cards consume.
 *
 * The stats, affected-method lists, hop counts, and graph are ALL derived from the real parsed
 * codebase. The optional LLM narrative is GIVEN those verified facts to explain (not recompute),
 * so it can never contradict the structured cards.
 */
@Service
public class ImpactAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ImpactAnalysisService.class);

    private final CallGraphService graph;
    private final ImpactAgentService agent;

    public ImpactAnalysisService(CallGraphService graph, ImpactAgentService agent) {
        this.graph = graph;
        this.agent = agent;
    }

    public ImpactReport analyze(String query) {
        return analyze(query, false);
    }

    public ImpactReport analyze(String query, boolean withNarrative) {
        MethodRef target = resolve(query);
        if (target == null) {
            return new ImpactReport(query, null, false, null,
                    "No method named '" + query + "' was found in the codebase.",
                    new ImpactReport.Stats(0, 0, 0, 0),
                    List.of(), List.of(),
                    new ImpactReport.Graph(List.of(), List.of()), "");
        }

        String id = target.id();
        List<ImpactNode> impact = graph.transitiveImpact(id);

        int methodsAffected = impact.size();
        int filesTouched = (int) impact.stream().map(ImpactNode::filePath).distinct().count();
        int maxDepth = impact.stream().mapToInt(ImpactNode::distance).max().orElse(0);
        int directCallers = graph.directCallers(id).size();

        List<ImpactReport.AffectedMethod> direct = new ArrayList<>();
        List<ImpactReport.AffectedMethod> behavioral = new ArrayList<>();
        for (ImpactNode n : impact) {
            ImpactReport.AffectedMethod am = toAffected(n);
            if (n.distance() <= 1) direct.add(am);
            else behavioral.add(am);
        }

        String risk = riskLevel(methodsAffected, maxDepth);
        String summary = summary(target.simpleName(), risk);
        ImpactReport.Graph g = buildGraph(id, target.simpleName(), impact);

        // grounded narrative: hand the model the verified facts to explain
        String report;
        if (!withNarrative) {
            report = "";
        } else {
            try {
                report = agent.narrate(target.simpleName(), risk, summary,
                        methodsAffected, filesTouched, maxDepth, directCallers,
                        formatList(direct), formatList(behavioral));
            } catch (Exception e) {
                log.warn("Narration failed, returning structured data only: {}", e.getMessage());
                report = "(The written narrative is unavailable right now, but the structured analysis "
                       + "above is computed directly from the call graph.)";
            }
        }

        return new ImpactReport(query, id, true, risk, summary,
                new ImpactReport.Stats(methodsAffected, filesTouched, maxDepth, directCallers),
                direct, behavioral, g, report);
    }

    private MethodRef resolve(String query) {
        List<MethodRef> matches = graph.findByName(query);
        if (matches.isEmpty()) return null;
        return matches.stream()
                .filter(m -> m.simpleName().equalsIgnoreCase(query))
                .findFirst()
                .orElse(matches.get(0));
    }

    private String formatList(List<ImpactReport.AffectedMethod> ms) {
        if (ms.isEmpty()) return "";
        return ms.stream()
                .map(m -> "- " + m.name() + " (" + m.file() + ":" + m.line() + ", hop " + m.hop() + ")")
                .collect(Collectors.joining("\n"));
    }

    private ImpactReport.AffectedMethod toAffected(ImpactNode n) {
        int line = graph.getNode(n.methodId()).map(MethodRef::startLine).orElse(-1);
        return new ImpactReport.AffectedMethod(shortName(n.methodId()), relFile(n.filePath()), line, n.distance());
    }

    private ImpactReport.Graph buildGraph(String targetId, String targetName, List<ImpactNode> impact) {
        List<ImpactReport.Node> nodes = new ArrayList<>();
        nodes.add(new ImpactReport.Node(targetId, targetName, 0));
        for (ImpactNode n : impact) {
            nodes.add(new ImpactReport.Node(n.methodId(), shortName(n.methodId()), n.distance()));
        }
        Set<String> allIds = nodes.stream().map(ImpactReport.Node::id).collect(Collectors.toSet());
        List<ImpactReport.Edge> edges = new ArrayList<>();
        for (String nodeId : allIds) {
            for (String caller : graph.directCallers(nodeId)) {
                if (allIds.contains(caller)) {
                    edges.add(new ImpactReport.Edge(caller, nodeId));
                }
            }
        }
        return new ImpactReport.Graph(nodes, edges);
    }

    private String riskLevel(int affected, int depth) {
        if (affected >= 10 || depth >= 4) return "High";
        if (affected >= 3 || depth >= 2) return "Medium";
        return "Low";
    }

    private String summary(String name, String risk) {
        return switch (risk) {
            case "High" -> name + " has wide downstream impact — change with caution and test thoroughly.";
            case "Medium" -> name + " has moderate downstream impact — review callers before shipping.";
            default -> name + " has limited downstream impact — likely safe to change.";
        };
    }

    private String shortName(String id) {
        String beforeParen = id.contains("(") ? id.substring(0, id.indexOf('(')) : id;
        String[] parts = beforeParen.split("\\.");
        if (parts.length >= 2) return parts[parts.length - 2] + "." + parts[parts.length - 1];
        return beforeParen;
    }

    private String relFile(String path) {
        String norm = path.replace('\\', '/');
        int i = norm.indexOf("src/");
        return i >= 0 ? norm.substring(i) : norm;
    }
}