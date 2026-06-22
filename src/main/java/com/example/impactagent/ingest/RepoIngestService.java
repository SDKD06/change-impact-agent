package com.example.impactagent.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.example.impactagent.graph.CallGraphService;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;

/**
 * Ingestion: chunk the repo by METHOD (not arbitrary character counts) and embed each method.
 * Code-aware chunking keeps each function whole, which is what makes semantic code search work.
 */
@Service
public class RepoIngestService {

    private static final Logger log = LoggerFactory.getLogger(RepoIngestService.class);

    private final VectorStore vectorStore;
    private final CallGraphService callGraph;

    @Value("${impactagent.repo-path}")
    private String repoPath;

    public RepoIngestService(VectorStore vectorStore, CallGraphService callGraph) {
        this.vectorStore = vectorStore;
        this.callGraph = callGraph;
    }

    /** Build the call graph and embed every method. Run once before querying. */
    public IngestResult ingest() {
        Path root = Paths.get(repoPath);

        // 1. the deterministic backbone
        callGraph.build(repoPath);

        // 2. the semantic index (one Document per method)
        List<Document> docs = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                  .forEach(p -> docs.addAll(chunkFile(p)));
        } catch (IOException e) {
            throw new RuntimeException("Failed to walk repo: " + repoPath, e);
        }

        if (!docs.isEmpty()) {
            // batch to avoid oversized embedding requests
            int batch = 100;
            for (int i = 0; i < docs.size(); i += batch) {
                vectorStore.add(docs.subList(i, Math.min(i + batch, docs.size())));
            }
        }
        log.info("Ingested {} method chunks from {}", docs.size(), repoPath);
        return new IngestResult(docs.size());
    }

    private List<Document> chunkFile(Path file) {
        List<Document> out = new ArrayList<>();
        try {
            var cu = StaticJavaParser.parse(file);
            for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
                String body = md.toString();
                String simpleName = md.getNameAsString();
                int start = md.getBegin().map(pos -> pos.line).orElse(-1);
                int end = md.getEnd().map(pos -> pos.line).orElse(-1);

                Map<String, Object> metadata = Map.of(
                        "file", file.toString(),
                        "method", simpleName,
                        "startLine", start,
                        "endLine", end
                );
                out.add(new Document(body, metadata));
            }
        } catch (Exception e) {
            log.debug("Skipping unparseable file {}: {}", file, e.getMessage());
        }
        return out;
    }

    public record IngestResult(int methodsIndexed) {}
}
