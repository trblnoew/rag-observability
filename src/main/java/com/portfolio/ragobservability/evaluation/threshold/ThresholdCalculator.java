package com.portfolio.ragobservability.evaluation.threshold;

import java.util.List;

public final class ThresholdCalculator {
    private ThresholdCalculator() {}
    public static final List<Double> THRESHOLDS = List.of(-1.0, 0.3, 0.4, 0.5, 0.7);
    public record Score(String queryId, String category, String expectedDocument, double threshold, int resultCount,
                        Integer rank, Boolean hit, Double rr, Double topSimilarity) {}
    public record Comparison(double threshold, int relatedCount, int unrelatedCount, double hitRate, double mrr,
                             double relatedEmptyRate, double unrelatedReturnRate, double averageResultCount,
                             List<Score> scores, List<String> lostAnswers, List<String> emptyRelated, List<String> returnedUnrelated) {
        public int count(String category) { return (int)scores.stream().filter(s -> s.category().equals(category)).count(); }
        public Double hitRate(String category) {
            var rows = scores.stream().filter(s -> s.category().equals(category) && s.hit()!=null).toList();
            return rows.isEmpty() ? null : rows.stream().filter(s -> s.hit()).count()/(double)rows.size();
        }
        public Double mrr(String category) {
            var rows = scores.stream().filter(s -> s.category().equals(category) && s.rr()!=null).toList();
            return rows.isEmpty() ? null : rows.stream().mapToDouble(Score::rr).average().orElseThrow();
        }
        public Double returnRate(String category) {
            var rows = scores.stream().filter(s -> s.category().equals(category)).toList();
            return rows.isEmpty() ? null : rows.stream().filter(s -> s.resultCount()>0).count()/(double)rows.size();
        }
    }
    public static Score score(ThresholdBaseline.Query query, double threshold) {
        if (!Double.isFinite(threshold) || threshold < -1 || threshold > 1) throw new IllegalArgumentException("INVALID_THRESHOLD");
        var hits = query.hits().stream().filter(h -> h.similarity() >= threshold).toList();
        Integer rank = null;
        for (int i=0; i<hits.size(); i++) if (query.related() && hits.get(i).filename().equals(query.expectedDocument())) { rank=i+1; break; }
        return new Score(query.id(), query.category(), query.expectedDocument(), threshold, hits.size(), rank,
                query.related() ? rank != null : null, query.related() ? (rank == null ? 0.0 : 1.0/rank) : null,
                hits.isEmpty() ? null : hits.getFirst().similarity());
    }
    public static List<Comparison> compare(ThresholdBaseline baseline) {
        return compare(baseline, THRESHOLDS);
    }
    public static List<Double> parseThresholds(String text) {
        return normalize(java.util.Arrays.stream(text.split(",", -1)).map(String::strip).map(Double::parseDouble).toList());
    }
    private static List<Double> normalize(List<Double> thresholds) {
        var sorted = new java.util.TreeSet<Double>(); sorted.add(-1.0);
        for (Double t : thresholds) {
            if (t==null || !Double.isFinite(t) || t < -1 || t > 1) throw new IllegalArgumentException("INVALID_THRESHOLD");
            sorted.add(t == 0 ? 0.0 : t);
        }
        return List.copyOf(sorted);
    }
    public static List<Comparison> compare(ThresholdBaseline baseline, List<Double> thresholds) {
        return normalize(thresholds).stream().map(t -> {
            var scores = baseline.queries().stream().map(q -> score(q, t)).toList();
            var related = scores.stream().filter(s -> s.hit() != null).toList();
            var unrelated = scores.stream().filter(s -> s.hit() == null).toList();
            var lost = baseline.queries().stream().filter(q -> q.related() && Boolean.TRUE.equals(score(q,-1).hit())
                    && Boolean.FALSE.equals(score(q,t).hit())).map(ThresholdBaseline.Query::id).toList();
            var empty = related.stream().filter(s -> s.resultCount()==0).map(Score::queryId).toList();
            var returned = unrelated.stream().filter(s -> s.resultCount()>0).map(Score::queryId).toList();
            return new Comparison(t, related.size(), unrelated.size(), related.stream().filter(Score::hit).count()/(double)related.size(),
                    related.stream().mapToDouble(Score::rr).average().orElseThrow(), empty.size()/(double)related.size(),
                    returned.size()/(double)unrelated.size(), scores.stream().mapToInt(Score::resultCount).average().orElseThrow(),
                    scores, lost, empty, returned);
        }).toList();
    }
}
