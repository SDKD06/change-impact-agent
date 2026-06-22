package com.example.impactagent.graph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.utils.SourceRoot;

/**
 * Builds a deterministic call graph from Java source.
 *
 * This is the backbone of the whole project: the LLM does NOT guess who calls what.
 * It asks this service, which knows the real edges. The graph gives precision the model
 * can't; the model gives explanation the graph can't.
 */
@Service
public class CallGraphService {

    private static final Logger log = LoggerFactory.getLogger(CallGraphService.class);

    // node id -> method metadata
    private final Map<String, MethodRef> nodes = new HashMap<>();
    // callee id -> set of caller ids   (REVERSE edges: this is what powers impact analysis)
    private final Map<String, Set<String>> callers = new HashMap<>();
    // caller id -> set of callee ids   (forward edges)
    private final Map<String, Set<String>> callees = new HashMap<>();

    private volatile boolean built = false;

    /**
     * Parse every .java file under repoPath and build the graph.
     * Call this once at ingestion time.
     */
    public synchronized void build(String repoPath) {
        nodes.clear();
        callers.clear();
        callees.clear();

        Path root = Paths.get(repoPath);
        List<Path> sourceRoots = findSourceRoots(root);
        if (sourceRoots.isEmpty()) {
            sourceRoots = List.of(root); // fall back to the repo root itself
        }

        CombinedTypeSolver typeSolver = new CombinedTypeSolver(new ReflectionTypeSolver());
        for (Path sr : sourceRoots) {
            typeSolver.add(new JavaParserTypeSolver(sr));
        }
        ParserConfiguration config = new ParserConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(typeSolver));

        int parsed = 0, edges = 0, unresolved = 0;

        for (Path sr : sourceRoots) {
            SourceRoot sourceRoot = new SourceRoot(sr, config);
            List<CompilationUnit> units = new ArrayList<>();
            try {
                sourceRoot.tryToParseParallelized().forEach(r ->
                        r.getResult().ifPresent(units::add));
            } catch (Exception e) {
                log.warn("Could not parse source root {}: {}", sr, e.getMessage());
                continue;
            }

            for (CompilationUnit cu : units) {
                String file = cu.getStorage().map(s -> s.getPath().toString()).orElse("<unknown>");

                // 1. register every method as a node
                for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
                    try {
                        String id = md.resolve().getQualifiedSignature();
                        int start = md.getBegin().map(p -> p.line).orElse(-1);
                        int end = md.getEnd().map(p -> p.line).orElse(-1);
                        nodes.put(id, new MethodRef(id, md.getNameAsString(), file, start, end));
                        parsed++;
                    } catch (Exception ex) {
                        unresolved++;
                    }
                }

                // 2. resolve every call inside each method -> add an edge
                for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
                    String callerId;
                    try {
                        callerId = md.resolve().getQualifiedSignature();
                    } catch (Exception ex) {
                        continue;
                    }
                    for (MethodCallExpr call : md.findAll(MethodCallExpr.class)) {
                        try {
                            ResolvedMethodDeclaration target = call.resolve();
                            String calleeId = target.getQualifiedSignature();
                            callees.computeIfAbsent(callerId, k -> new HashSet<>()).add(calleeId);
                            callers.computeIfAbsent(calleeId, k -> new HashSet<>()).add(callerId);
                            edges++;
                        } catch (Exception ex) {
                            // call into a library / unresolved generic - skip it (best effort)
                            unresolved++;
                        }
                    }
                }
            }
        }

        built = true;
        log.info("Call graph built: {} methods, {} edges, {} unresolved references skipped",
                parsed, edges, unresolved);
    }

    public boolean isBuilt() {
        return built;
    }

    /** Fuzzy lookup: turn a human name like "validateToken" into candidate node ids. */
    public List<MethodRef> findByName(String name) {
        String needle = name.toLowerCase();
        return nodes.values().stream()
                .filter(m -> m.simpleName().toLowerCase().equals(needle)
                        || m.id().toLowerCase().contains(needle))
                .collect(Collectors.toList());
    }

    public Optional<MethodRef> getNode(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    /** Direct callers of a method - the things that would feel a change first. */
    public Set<String> directCallers(String id) {
        return callers.getOrDefault(id, Set.of());
    }

    /**
     * Transitive callers: everything that depends on this method, directly or indirectly.
     * THIS is the change-impact set. Implemented as a reverse BFS over the call graph.
     */
    public List<ImpactNode> transitiveImpact(String id) {
        List<ImpactNode> impact = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        Map<String, Integer> depth = new HashMap<>();

        queue.add(id);
        depth.put(id, 0);
        seen.add(id);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            int d = depth.get(current);
            for (String caller : directCallers(current)) {
                if (seen.add(caller)) {
                    depth.put(caller, d + 1);
                    MethodRef ref = nodes.get(caller);
                    impact.add(new ImpactNode(caller,
                            ref != null ? ref.filePath() : "<external>",
                            d + 1));
                    queue.add(caller);
                }
            }
        }
        impact.sort(Comparator.comparingInt(ImpactNode::distance));
        return impact;
    }

    private List<Path> findSourceRoots(Path root) {
        List<Path> roots = new ArrayList<>();
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isDirectory)
                    .filter(p -> p.endsWith(Paths.get("src", "main", "java"))
                            || p.endsWith(Paths.get("src", "java")))
                    .forEach(roots::add);
        } catch (IOException e) {
            log.warn("Could not scan for source roots under {}: {}", root, e.getMessage());
        }
        return roots;
    }

    /** A method affected by the change, plus how far it sits from the changed method. */
    public record ImpactNode(String methodId, String filePath, int distance) {}
}