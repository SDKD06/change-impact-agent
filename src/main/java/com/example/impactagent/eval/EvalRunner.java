package com.example.impactagent.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.example.impactagent.graph.CallGraphService;

/**
 * Evaluation harness for the call-graph's structural accuracy.
 *
 * Methodology: a ground-truth set of (method -> its true direct callers) is established by
 * manually reading the source. We then compare the graph's directCallers() against it and
 * compute precision and recall. Precision answers "does the graph ever invent a caller?",
 * recall answers "does it find every real caller?".
 *
 * The default set below was hand-verified against MaxHeap.java in TheAlgorithms/Java.
 */
@Service
public class EvalRunner {

    private final CallGraphService graph;

    public EvalRunner(CallGraphService graph) {
        this.graph = graph;
    }

    public record EvalCase(String methodId, Set<String> expectedDirectCallers) {}

    public record CaseResult(String methodId, int expected, int predicted, int correct,
                             double recall, double precision) {}

    public record EvalSummary(int cases, double microRecall, double microPrecision,
                              int totalExpected, int totalPredicted, int totalCorrect,
                              List<CaseResult> details) {}

    public EvalSummary run(List<EvalCase> cases) {
        List<CaseResult> results = new ArrayList<>();
        int te = 0, tp = 0, tc = 0;
        for (EvalCase c : cases) {
            Set<String> predicted = graph.directCallers(c.methodId());
            Set<String> expected = c.expectedDirectCallers();
            long correct = predicted.stream().filter(expected::contains).count();
            double recall = expected.isEmpty() ? 1.0 : (double) correct / expected.size();
            double precision = predicted.isEmpty() ? 1.0 : (double) correct / predicted.size();
            results.add(new CaseResult(c.methodId(), expected.size(), predicted.size(),
                    (int) correct, round(recall), round(precision)));
            te += expected.size(); tp += predicted.size(); tc += correct;
        }
        double microR = te == 0 ? 1.0 : (double) tc / te;
        double microP = tp == 0 ? 1.0 : (double) tc / tp;
        return new EvalSummary(cases.size(), round(microR), round(microP), te, tp, tc, results);
    }

    /** Hand-verified ground truth: MaxHeap's internal call structure. */
    public List<EvalCase> defaultSet() {
        String p = "com.thealgorithms.datastructures.heaps.MaxHeap.";
        String heapElem = "com.thealgorithms.datastructures.heaps.HeapElement";
        return List.of(
                new EvalCase(p + "swap(int, int)", Set.of(p + "toggleDown(int)", p + "toggleUp(int)")),
                new EvalCase(p + "toggleDown(int)", Set.of(p + "deleteElement(int)")),
                new EvalCase(p + "toggleUp(int)", Set.of(p + "deleteElement(int)", p + "insertElement(" + heapElem + ")")),
                new EvalCase(p + "deleteElement(int)", Set.of(p + "extractMax()")),
                new EvalCase(p + "extractMax()", Set.of(p + "getElement()"))
        );
    }

    private double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}