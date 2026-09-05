package com.portfolio.ragobservability.evaluation.topk;

import java.util.*;
import com.portfolio.ragobservability.evaluation.threshold.ThresholdBaseline;

public final class TopKCalculator {
    private TopKCalculator() {}
    public static final double THRESHOLD = 0.3;
    public static final List<Integer> KS = List.of(1, 2, 3, 5);
    public record Score(ThresholdBaseline.Query query, int k, int resultCount, long documentCount,
                        Integer rank, Boolean hit, Double rr) {}
    public record Comparison(int k, List<Score> scores) {
        public Comparison { scores = List.copyOf(scores); }
        public List<Score> related() { return scores.stream().filter(s -> s.hit()!=null).toList(); }
        public List<Score> negative() { return scores.stream().filter(s -> s.hit()==null).toList(); }
        public List<Score> category(String name) { return scores.stream().filter(s -> s.query().category().equals(name)).toList(); }
        public double meanChunks() { return scores.stream().mapToInt(Score::resultCount).average().orElseThrow(); }
        public double meanDocuments() { return scores.stream().mapToLong(Score::documentCount).average().orElseThrow(); }
        public Double emptyRate() { return rate(related(), s -> s.resultCount()==0); }
    }
    public static Double hitRate(List<Score> rows) { return rate(rows, s -> Boolean.TRUE.equals(s.hit())); }
    public static Double mrr(List<Score> rows) { return rows.isEmpty() ? null : rows.stream().mapToDouble(Score::rr).average().orElseThrow(); }
    public static Double returnRate(List<Score> rows) { return rate(rows, s -> s.resultCount()>0); }
    private static Double rate(List<Score> rows, java.util.function.Predicate<Score> predicate) {
        return rows.isEmpty() ? null : rows.stream().filter(predicate).count()/(double)rows.size();
    }
    public static Score score(ThresholdBaseline.Query query, int k) {
        if (!KS.contains(k)) throw new IllegalArgumentException("UNSUPPORTED_TOP_K");
        var hits = query.hits().stream().filter(h -> h.similarity() >= THRESHOLD).limit(k).toList();
        Integer rank = null;
        for (int i=0; i<hits.size(); i++) {
            if (query.related() && hits.get(i).filename().equals(query.expectedDocument())) { rank=i+1; break; }
        }
        return new Score(query, k, hits.size(), hits.stream().map(ThresholdBaseline.Hit::documentId).distinct().count(),
                rank, query.related() ? rank!=null : null, query.related() ? rank==null ? 0.0 : 1.0/rank : null);
    }
    public static List<Comparison> compare(ThresholdBaseline baseline) {
        return KS.stream().map(k -> new Comparison(k, baseline.queries().stream().map(q -> score(q,k)).toList())).toList();
    }
}

