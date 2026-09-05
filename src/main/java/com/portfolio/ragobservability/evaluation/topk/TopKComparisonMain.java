package com.portfolio.ragobservability.evaluation.topk;

import java.nio.file.Path;
import com.portfolio.ragobservability.evaluation.threshold.ThresholdBaseline;

/** Offline entry point: no Spring context, environment secrets or network clients. */
public final class TopKComparisonMain {
    private TopKComparisonMain() {}
    public static void main(String[] args) { System.exit(run(args)); }
    public static int run(String[] args) {
        if (args.length!=4) {
            System.err.println("Usage: TopKComparisonMain <baselineDir> <outputRoot> <gitCommit> <gitDirty>");
            return 1;
        }
        try {
            System.out.println("Top-K=1/2/3/5; threshold=0.3; API calls=0");
            var baseline = ThresholdBaseline.load(Path.of(args[0]));
            var output = new TopKReport().write(Path.of(args[1]), baseline, args[2], args[3]);
            System.out.println("Top-K comparison COMPLETE: " + output.toAbsolutePath());
            return 0;
        } catch (Exception e) {
            System.err.println("TOPK_COMPARISON_FAILED: " + e.getClass().getSimpleName());
            return 1;
        }
    }
}

