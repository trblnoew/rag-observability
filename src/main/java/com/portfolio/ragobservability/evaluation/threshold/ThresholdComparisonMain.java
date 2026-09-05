package com.portfolio.ragobservability.evaluation.threshold;

import java.nio.file.Path;

/** Plain Java entry point: deliberately does not create a Spring context or load .env. */
public final class ThresholdComparisonMain {
    private ThresholdComparisonMain() {}
    public static void main(String[] args) { System.exit(run(args)); }
    public static int run(String[] args) {
        if (args.length != 4 && args.length != 5) {
            System.err.println("Usage: ThresholdComparisonMain <baselineDir> <outputRoot> <gitCommit> <gitDirty>");
            return 1;
        }
        try {
            var baseline = ThresholdBaseline.load(Path.of(args[0]));
            var comparisons = ThresholdCalculator.compare(baseline, args.length == 5 ? ThresholdCalculator.parseThresholds(args[4]) : ThresholdCalculator.THRESHOLDS);
            Path output = new ThresholdReport().write(Path.of(args[1]), baseline, comparisons, args[2], args[3]);
            System.out.println("Threshold comparison COMPLETE: " + output.toAbsolutePath());
            return 0;
        } catch (ThresholdBaseline.InvalidBaselineException e) {
            System.err.println("INVALID_BASELINE: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("THRESHOLD_COMPARISON_FAILED: " + e.getClass().getSimpleName());
        }
        return 1;
    }
}
