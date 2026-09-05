package com.portfolio.ragobservability.evaluation.threshold;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

public final class ThresholdReport {
    public Path write(Path root, ThresholdBaseline baseline, List<ThresholdCalculator.Comparison> comparisons,
                      String gitCommit, String gitDirty) throws IOException {
        Instant now = Instant.now();
        StringWriter comparisonText = new StringWriter(), queryText = new StringWriter();
        try (CSVPrinter csv = new CSVPrinter(comparisonText, CSVFormat.RFC4180);
             CSVPrinter queries = new CSVPrinter(queryText, CSVFormat.RFC4180)) {
            csv.printRecord("threshold", "relatedCount", "unrelatedCount", "totalCount", "hitAt5", "mrrAt5",
                    "relatedEmptyRate", "unrelatedReturnRate", "averageResultCount", "negativeCount", "negativeReturnRate",
                    "hardNegativeCount", "hardNegativeReturnRate", "directCount", "directHitAt5", "directMrrAt5", "paraphrasedCount", "paraphrasedHitAt5", "paraphrasedMrrAt5");
            queries.printRecord("queryId", "category", "expectedDocument", "threshold", "resultCount",
                    "firstRelevantRank", "hit", "reciprocalRank", "topSimilarity", "review", "question", "judgmentReason");
            for (var c : comparisons) {
                csv.printRecord(c.threshold(), c.relatedCount(), c.count("UNRELATED"), c.scores().size(), c.hitRate(), c.mrr(),
                        c.relatedEmptyRate(), c.returnRate("UNRELATED"), c.averageResultCount(), c.unrelatedCount(), c.unrelatedReturnRate(),
                        c.count("HARD_NEGATIVE"), c.returnRate("HARD_NEGATIVE"), c.count("DIRECT"), c.hitRate("DIRECT"), c.mrr("DIRECT"),
                        c.count("PARAPHRASED"), c.hitRate("PARAPHRASED"), c.mrr("PARAPHRASED"));
                for (var s : c.scores()) {
                    var q = baseline.queries().stream().filter(query -> query.id().equals(s.queryId())).findFirst().orElseThrow();
                    queries.printRecord(s.queryId(), s.category(), s.expectedDocument(), s.threshold(),
                        s.resultCount(), s.rank(), s.hit(), s.rr(), s.topSimilarity(), review(s), q.question(), q.judgmentReason());
                }
            }
        }
        StringBuilder md = new StringBuilder("# Threshold comparison\n\nCOMPLETE\n\n");
        md.append("- Comparison started (UTC): ").append(now).append('\n');
        md.append("- Comparison Git commit: ").append(escape(gitCommit)).append("; dirty: ").append(escape(gitDirty)).append('\n');
        md.append("- Source baseline: ").append(escape(baseline.source().toString())).append('\n');
        baseline.hashes().forEach((file, hash) -> md.append("- Input SHA-256 (").append(file).append("): ").append(hash).append('\n'));
        md.append("\n## Original run metadata\n\n");
        baseline.metadata().forEach((key, value) -> md.append("- ").append(key).append(": ").append(escape(value)).append('\n'));
        var first = comparisons.getFirst();
        md.append("\n## Comparison\n\nRelated denominator: ").append(first.relatedCount())
                .append("; unrelated denominator: ").append(first.count("UNRELATED"))
                .append("; hard-negative denominator: ").append(first.count("HARD_NEGATIVE"))
                .append("; all-negative denominator: ").append(first.unrelatedCount())
                .append("; average result count denominator: ").append(first.scores().size()).append(".\n\n");
        md.append("| Threshold | Hit@5 | MRR@5 | Related empty rate | All-negative return rate | Mean chunks |\n|---|---|---|---|---|---|\n");
        for (var c : comparisons) md.append("| ").append(c.threshold()).append(" | ").append(f(c.hitRate())).append(" | ")
                .append(f(c.mrr())).append(" | ").append(f(c.relatedEmptyRate())).append(" | ")
                .append(f(c.unrelatedReturnRate())).append(" | ").append(f(c.averageResultCount())).append(" |\n");
        md.append("\n| Threshold | DIRECT Hit / MRR | PARAPHRASED Hit / MRR | HARD_NEGATIVE return rate | UNRELATED return rate |\n|---|---|---|---|---|\n");
        for (var c : comparisons) md.append("| ").append(c.threshold()).append(" | ").append(f(c.hitRate("DIRECT"))).append(" / ")
                .append(f(c.mrr("DIRECT"))).append(" | ").append(f(c.hitRate("PARAPHRASED"))).append(" / ").append(f(c.mrr("PARAPHRASED")))
                .append(" | ").append(f(c.returnRate("HARD_NEGATIVE"))).append(" | ").append(f(c.returnRate("UNRELATED"))).append(" |\n");
        if (baseline.queries().stream().anyMatch(q -> q.judgmentReason()!=null)) {
            md.append("\n## Frozen questions and judgments\n\n| ID | Category | Question | Expected document | Reason |\n|---|---|---|---|---|\n");
            for (var q : baseline.queries()) md.append("| ").append(q.id()).append(" | ").append(q.category()).append(" | ")
                    .append(escape(q.question())).append(" | ").append(escape(q.expectedDocument())).append(" | ").append(escape(q.judgmentReason())).append(" |\n");
        }
        md.append("\n## Query changes\n\n");
        for (var c : comparisons) {
            md.append("### Threshold ").append(c.threshold()).append("\n\n");
            md.append("- Lost answers versus -1.0: ").append(ids(c.lostAnswers())).append('\n');
            md.append("- Empty related queries: ").append(ids(c.emptyRelated())).append('\n');
            md.append("- Returned negative queries (HARD_NEGATIVE + UNRELATED): ").append(ids(c.returnedUnrelated())).append('\n');
            md.append("- Nonempty but wrong-document results: ").append(ids(c.scores().stream()
                    .filter(s -> Boolean.FALSE.equals(s.hit()) && s.resultCount()>0).map(ThresholdCalculator.Score::queryId).toList())).append("\n\n");
        }
        md.append("## Interpretation\n\n문서 수준 정답 여부를 평가합니다. 정답 문서의 Chunk라도 답변 근거가 충분한지는 별도 검토가 필요합니다.\n\n")
                .append("저장된 Top-5에 더 높은 threshold만 적용했습니다. 순위의 문서 중복은 제거하지 않았으며, 5위 밖의 결과를 복원하거나 Top-K를 늘리지 않았습니다.\n\n")
                .append("시간을 새로 측정하지 않았고 원래 latency를 설정별 성능으로 재사용하지 않았습니다. DB 및 외부 API 호출은 없습니다.\n\n")
                .append("HARD_NEGATIVE는 주제는 비슷하지만 요구한 답이 없는 질문입니다. 결과가 남았다고 LLM이 반드시 오답을 생성한다는 뜻은 아닙니다.\n\n")
                .append("한 데이터셋의 결과만 비교합니다. 기존·신규 점수는 합산하지 않으며 최적 threshold를 자동 선택하거나 RAG 설정을 바꾸지 않습니다.\n");
        Files.createDirectories(root);
        Path output = Files.createDirectory(root.resolve("threshold-comparison-" + now.toString().replace(':','-') + "-" + UUID.randomUUID()));
        // A COMPLETE summary is the final commit marker. Earlier output failures never leave a success report.
        Files.writeString(output.resolve("comparison.csv"), comparisonText.toString(), StandardOpenOption.CREATE_NEW);
        Files.writeString(output.resolve("query-comparison.csv"), queryText.toString(), StandardOpenOption.CREATE_NEW);
        Path pending = output.resolve("summary.pending");
        Files.writeString(pending, md.toString(), StandardOpenOption.CREATE_NEW);
        Files.move(pending, output.resolve("summary.md"));
        return output;
    }
    private static String review(ThresholdCalculator.Score s) {
        if (s.hit()==null) return s.category() + (s.resultCount()==0 ? "_EMPTY" : "_RETURNED");
        return s.resultCount()==0 ? "RELATED_EMPTY" : s.hit() ? "HIT" : "NONEMPTY_MISS";
    }
    private static String ids(List<String> ids) { return ids.isEmpty() ? "none" : String.join(", ", ids); }
    private static String f(Double d) { return d == null ? "" : String.format(Locale.ROOT,"%.4f",d); }
    private static String escape(String s) { return s == null ? "" : s.replace("\r", " ").replace("\n", " ").replace("|", "\\|"); }
}
