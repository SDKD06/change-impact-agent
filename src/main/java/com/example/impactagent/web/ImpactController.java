package com.example.impactagent.web;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.impactagent.graph.CallGraphService;
import com.example.impactagent.ingest.RepoIngestService;

@RestController
@RequestMapping("/api")
public class ImpactController {

    private final RepoIngestService ingestService;
    private final ImpactAnalysisService analysisService;
    private final CallGraphService callGraph;

    public ImpactController(RepoIngestService ingestService,
                            ImpactAnalysisService analysisService,
                            CallGraphService callGraph) {
        this.ingestService = ingestService;
        this.analysisService = analysisService;
        this.callGraph = callGraph;
    }

    /** Step 1: build the call graph + embed the repo. Run once after startup. */
    @PostMapping("/ingest")
    public ResponseEntity<?> ingest() {
        var result = ingestService.ingest();
        return ResponseEntity.ok(Map.of(
                "methodsIndexed", result.methodsIndexed(),
                "graphBuilt", callGraph.isBuilt()));
    }

    /**
     * Step 2: structured impact analysis for a method.
     * Returns instantly with all the graph-derived data.
     * Pass withReport=true to also generate the (slower) AI written narrative.
     */
    @PostMapping("/impact")
    public ResponseEntity<?> impact(@RequestParam String method,
                                    @RequestParam(defaultValue = "false") boolean withReport) {
        if (!callGraph.isBuilt()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Call graph not built yet. POST /api/ingest first."));
        }
        return ResponseEntity.ok(analysisService.analyze(method, withReport));
    }
}