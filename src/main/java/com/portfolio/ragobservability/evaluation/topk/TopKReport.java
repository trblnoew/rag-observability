package com.portfolio.ragobservability.evaluation.topk;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.apache.commons.csv.*;
import com.portfolio.ragobservability.evaluation.threshold.ThresholdBaseline;
import static com.portfolio.ragobservability.evaluation.topk.TopKCalculator.*;

public final class TopKReport {
    public Path write(Path root, ThresholdBaseline baseline, String commit, String dirty) throws IOException {
        var comparisons = compare(baseline);
        StringWriter overall = new StringWriter(), detail = new StringWriter();
        StringBuilder md = new StringBuilder("# Top-K comparison\n\nCOMPLETE\n\n");
        Instant now = Instant.now();
        md.append("- Comparison UTC: ").append(now).append("\n- Comparison Git: ").append(escape(commit))
          .append("; dirty: ").append(escape(dirty)).append("\n- Input: ").append(escape(baseline.source().toString()))
          .append("\n- Fixed threshold: 0.3; K: 1/2/3/5; API calls: 0\n\n## Source provenance\n\n");
        baseline.hashes().forEach((key,value) -> md.append("- SHA-256 ").append(key).append(": ").append(value).append('\n'));
        baseline.metadata().forEach((key,value) -> md.append("- ").append(key).append(": ").append(escape(value)).append('\n'));
        md.append("\n## Comparison\n\n| K | Hit@K | MRR@K | Mean chunks | Mean documents | Related empty | Negative return |\n|---|---|---|---|---|---|---|\n");
        try (var csv = new CSVPrinter(overall, CSVFormat.RFC4180); var rows = new CSVPrinter(detail, CSVFormat.RFC4180)) {
            csv.printRecord("topK","threshold","totalCount","relatedCount","negativeCount","hitAtK","mrrAtK","relatedEmptyRate",
                    "negativeReturnRate","meanChunkCount","meanDocumentCount","directCount","directHitAtK","directMrrAtK",
                    "paraphrasedCount","paraphrasedHitAtK","paraphrasedMrrAtK","hardNegativeCount","hardNegativeReturnRate","unrelatedCount","unrelatedReturnRate");
            rows.printRecord("queryId","category","question","expectedDocument","judgmentReason","topK","threshold",
                    "resultCount","documentCount","firstRelevantRank","hit","reciprocalRank");
            for (var c : comparisons) {
                var direct=c.category("DIRECT"); var para=c.category("PARAPHRASED");
                var hard=c.category("HARD_NEGATIVE"); var none=c.category("UNRELATED");
                csv.printRecord(c.k(),THRESHOLD,c.scores().size(),c.related().size(),c.negative().size(),hitRate(c.related()),mrr(c.related()),
                        c.emptyRate(),returnRate(c.negative()),c.meanChunks(),c.meanDocuments(),direct.size(),hitRate(direct),mrr(direct),
                        para.size(),hitRate(para),mrr(para),hard.size(),returnRate(hard),none.size(),returnRate(none));
                md.append("| ").append(c.k()).append(" | ").append(f(hitRate(c.related()))).append(" | ").append(f(mrr(c.related())))
                  .append(" | ").append(f(c.meanChunks())).append(" | ").append(f(c.meanDocuments())).append(" | ")
                  .append(f(c.emptyRate())).append(" | ").append(f(returnRate(c.negative()))).append(" |\n");
                for (var s : c.scores()) rows.printRecord(s.query().id(),s.query().category(),s.query().question(),s.query().expectedDocument(),
                        s.query().judgmentReason(),s.k(),THRESHOLD,s.resultCount(),s.documentCount(),s.rank(),s.hit(),s.rr());
            }
        }
        md.append("\n| K | DIRECT n / Hit / MRR | PARAPHRASED n / Hit / MRR | HARD_NEGATIVE n / return | UNRELATED n / return |\n|---|---|---|---|---|\n");
        for (var c : comparisons) {
            md.append("| ").append(c.k());
            for (String cat : List.of("DIRECT","PARAPHRASED")) {
                var group=c.category(cat); md.append(" | ").append(group.size()).append(" / ").append(f(hitRate(group))).append(" / ").append(f(mrr(group)));
            }
            for (String cat : List.of("HARD_NEGATIVE","UNRELATED")) {
                var group=c.category(cat); md.append(" | ").append(group.size()).append(" / ").append(f(returnRate(group)));
            }
            md.append(" |\n");
        }
        md.append("\nMetric denominators: related=").append(comparisons.getFirst().related().size())
          .append("; negative=").append(comparisons.getFirst().negative().size()).append("; means=").append(baseline.queries().size()).append(".\n");
        int previous=0;
        for (var c : comparisons) {
            final int prev=previous;
            md.append("\n### K=").append(c.k()).append("\n\n");
            md.append("- Lost versus K=5: ").append(ids(c.related().stream().filter(s -> !s.hit() && Boolean.TRUE.equals(score(s.query(),5).hit())).toList())).append('\n');
            md.append("- Nonempty miss: ").append(ids(c.related().stream().filter(s -> !s.hit() && s.resultCount()>0).toList())).append('\n');
            md.append("- Newly found versus ").append(prev==0 ? "no results" : "K="+prev).append(": ")
              .append(ids(c.related().stream().filter(s -> s.hit() && (prev==0 || !Boolean.TRUE.equals(score(s.query(),prev).hit()))).toList())).append('\n');
            previous=c.k();
        }
        md.append("\n## Frozen judgments\n\n| ID | Question | Expected document | Reason |\n|---|---|---|---|\n");
        for (var q : baseline.queries()) md.append("| ").append(q.id()).append(" | ").append(escape(q.question())).append(" | ")
                .append(escape(q.expectedDocument())).append(" | ").append(escape(q.judgmentReason())).append(" |\n");
        md.append("\n## Interpretation and limits\n\n")
          .append("고정 threshold에서 K≥1이면 1위의 존부는 같으므로 빈 결과율과 부정 질문 반환율은 변하지 않습니다. Top-K는 답 없는 질문 판별기가 아닙니다.\n\n")
          .append("동일 문서의 Chunk를 중복 제거하지 않습니다. 문서 수준 Hit가 같아도 근거의 충분함이나 RAG 답변 품질이 같다는 뜻은 아닙니다.\n\n")
          .append("Chunk 전문이 없으므로 token 수·LLM 비용 감소량을 계산하지 않습니다. 지연시간을 측정하거나 기존 latency를 K별 성능으로 재사용하지 않습니다.\n\n")
          .append("기존 600-token 생성 조건은 운영자 전제입니다. 기존·신규 데이터셋 점수는 합산하지 않습니다. 최적 K를 확정하지 않고 RAG threshold 0.5와 Top-K 5를 변경하지 않습니다.\n");
        Files.createDirectories(root);
        Path output=Files.createDirectory(root.resolve("topk-comparison-"+now.toString().replace(':','-')+"-"+UUID.randomUUID()));
        Files.writeString(output.resolve("comparison.csv"),overall.toString(),StandardOpenOption.CREATE_NEW);
        Files.writeString(output.resolve("query-comparison.csv"),detail.toString(),StandardOpenOption.CREATE_NEW);
        Files.writeString(output.resolve("summary.pending"),md.toString(),StandardOpenOption.CREATE_NEW);
        Files.move(output.resolve("summary.pending"),output.resolve("summary.md"));
        return output;
    }
    private static String ids(List<Score> scores) { return scores.isEmpty() ? "none" : String.join(", ",scores.stream().map(s -> s.query().id()).toList()); }
    private static String f(Double d) { return d==null ? "" : String.format(Locale.ROOT,"%.4f",d); }
    private static String escape(String s) { return s==null ? "" : s.replace("|","\\|").replace("\r"," ").replace("\n"," "); }
}

