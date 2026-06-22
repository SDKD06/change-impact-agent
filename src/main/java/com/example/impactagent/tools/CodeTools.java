package com.example.impactagent.tools;

import com.example.impactagent.graph.CallGraphService;
import com.example.impactagent.graph.MethodRef;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The agent's toolbox. The LLM decides which of these to call, in what order, to investigate
 * a change. The call-graph tools return GROUND TRUTH - the model must use them rather than
 * guessing who calls what.
 */
@Component
public class CodeTools {

    private final VectorStore vectorStore;
    private final CallGraphService callGraph;

    @Value("${impactagent.max-file-chars:8000}")
    private int maxFileChars;

    public CodeTools(VectorStore vectorStore, CallGraphService callGraph) {
        this.vectorStore = vectorStore;
        this.callGraph = callGraph;
    }

    @Tool(description = "Resolve a human method name (e.g. 'validateToken') into one or more " +
            "fully-qualified method ids. Always call this first to find the exact id to analyze.")
    public String findMethod(
            @ToolParam(description = "The simple method name to look up") String name) {
        List<MethodRef> matches = callGraph.findByName(name);
        if (matches.isEmpty()) return "No method found matching: " + name;
        return matches.stream()
                .map(m -> m.id() + "   (" + m.filePath() + ":" + m.startLine() + ")")
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "Return the full transitive impact set for a method: every method that " +
            "depends on it directly or indirectly. This is the authoritative 'what breaks' list, " +
            "computed from the real call graph. Each line shows the method, its file, and how many " +
            "hops away it is from the changed method.")
    public String getImpactSet(
            @ToolParam(description = "Fully-qualified method id from findMethod") String methodId) {
        var impact = callGraph.transitiveImpact(methodId);
        if (impact.isEmpty()) return "No callers found. This method appears to be a leaf / entry point.";
        return impact.stream()
                .map(n -> "[" + n.distance() + " hop(s)] " + n.methodId() + "   (" + n.filePath() + ")")
                .collect(Collectors.joining("\n"));
    }

    @Tool(description = "Return only the DIRECT callers of a method (1 hop away).")
    public String getDirectCallers(
            @ToolParam(description = "Fully-qualified method id") String methodId) {
        var callers = callGraph.directCallers(methodId);
        return callers.isEmpty() ? "No direct callers." : String.join("\n", callers);
    }

    @Tool(description = "Semantic search over the codebase. Use to find relevant methods by " +
            "describing behavior (e.g. 'where passwords are hashed') when you don't know names.")
    public String searchCode(
            @ToolParam(description = "Natural-language description of what to find") String query) {
        List<Document> hits = vectorStore.similaritySearch(
                SearchRequest.builder().query(query).topK(5).build());
        if (hits == null || hits.isEmpty()) return "No relevant code found.";
        return hits.stream().map(d -> {
            Object file = d.getMetadata().get("file");
            Object line = d.getMetadata().get("startLine");
            String snippet = d.getText();
            if (snippet.length() > 600) snippet = snippet.substring(0, 600) + "\n... (truncated)";
            return file + ":" + line + "\n" + snippet;
        }).collect(Collectors.joining("\n\n---\n\n"));
    }

    @Tool(description = "Read a source file (or the start of it) to inspect exact code. " +
            "Use after you've identified an affected file and need to explain HOW it breaks.")
    public String readFile(
            @ToolParam(description = "Absolute or repo-relative path to the file") String path) {
        try {
            String content = Files.readString(Paths.get(path));
            if (content.length() > maxFileChars) {
                content = content.substring(0, maxFileChars) + "\n... (truncated)";
            }
            return content;
        } catch (Exception e) {
            return "Could not read file: " + path + " (" + e.getMessage() + ")";
        }
    }
}
