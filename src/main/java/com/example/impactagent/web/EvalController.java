package com.example.impactagent.web;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.impactagent.eval.EvalRunner;
import com.example.impactagent.graph.CallGraphService;

/**
 * Runs the evaluation harness and returns precision/recall over the hand-verified ground-truth set.
 * POST /api/eval  (the repo must be ingested first so the graph exists).
 */
@RestController
@RequestMapping("/api")
public class EvalController {

    private final EvalRunner evalRunner;
    private final CallGraphService callGraph;

    public EvalController(EvalRunner evalRunner, CallGraphService callGraph) {
        this.evalRunner = evalRunner;
        this.callGraph = callGraph;
    }

    @PostMapping("/eval")
    public ResponseEntity<?> eval() {
        if (!callGraph.isBuilt()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Call graph not built yet. POST /api/ingest against TheAlgorithms/Java first."));
        }
        return ResponseEntity.ok(evalRunner.run(evalRunner.defaultSet()));
    }
}
