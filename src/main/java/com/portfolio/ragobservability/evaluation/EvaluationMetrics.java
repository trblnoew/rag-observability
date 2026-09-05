package com.portfolio.ragobservability.evaluation;

import java.util.List;
import com.portfolio.ragobservability.retrieval.SearchHit;

public final class EvaluationMetrics {
    private EvaluationMetrics() {}
    public record Score(Integer firstRelevantRank, Boolean hit, Double reciprocalRank) {}
    public static Score score(Long expectedDocumentId, List<SearchHit> hits) {
        if (expectedDocumentId == null) return new Score(null, null, null);
        for (int i = 0; i < hits.size(); i++) {
            if (hits.get(i).documentId() == expectedDocumentId)
                return new Score(i + 1, true, 1.0 / (i + 1));
        }
        return new Score(null, false, 0.0);
    }
    public static double hitRate(List<Score> scores) {
        return scores.stream().filter(s -> s.hit() != null).mapToDouble(s -> s.hit() ? 1 : 0).average().orElseThrow();
    }
    public static double mrr(List<Score> scores) {
        return scores.stream().filter(s -> s.reciprocalRank() != null).mapToDouble(Score::reciprocalRank).average().orElseThrow();
    }
    public static Double percentile(List<Double> values, double percentile) {
        if (!Double.isFinite(percentile) || percentile <= 0 || percentile > 1) throw new IllegalArgumentException("Invalid percentile");
        if (values.isEmpty()) return null;
        List<Double> sorted = values.stream().sorted().toList();
        return sorted.get((int) Math.ceil(percentile * sorted.size()) - 1);
    }
}
