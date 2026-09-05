package com.portfolio.ragobservability.evaluation.threshold;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.io.StringWriter;
import org.apache.commons.csv.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThresholdComparisonTests {
    @TempDir Path temp;
    private static final String SUMMARY = """
            # Retrieval baseline

            COMPLETE

            - Started (UTC): 2026-09-05T08:51:17Z
            - Git commit: unavailable; dirty: unknown
            - Dataset SHA-256: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
            - Provider/model/dimensions: fake / fake-1536 / 1536
            - Top-K: 5 chunks; minimum similarity: -1.0; cosine exact search
            - Corpus chunk-size provenance: 600 tokens (operator assumption; not persisted in schema)
            - Logical queries attempted: 15 / 15
            """;

    Path fixture(String filename) throws Exception {
        Path dir = Files.createDirectory(temp.resolve(UUID.randomUUID().toString()));
        Files.writeString(dir.resolve("summary.md"), SUMMARY);
        StringWriter q = new StringWriter(), h = new StringWriter();
        try (CSVPrinter queries = new CSVPrinter(q, CSVFormat.RFC4180); CSVPrinter hits = new CSVPrinter(h, CSVFormat.RFC4180)) {
            queries.printRecord("queryId","category","expectedDocument","resultCount","firstRelevantRank","hit","reciprocalRank","topSimilarity","latencyMs","errorCode");
            hits.printRecord("queryId","rank","documentId","originalFilename","chunkId","chunkIndex","similarity");
            for (int i=1; i<=15; i++) {
                boolean related = i<=12;
                queries.printRecord("Q"+i,related ? (i<=6 ? "DIRECT" : "PARAPHRASED") : "UNRELATED",related ? filename : null,
                        3,related ? 3 : null,related ? true : null,related ? 1.0/3 : null,.8,1.0,null);
                hits.printRecord("Q"+i,1,1,"other.md",1,0,.8);
                hits.printRecord("Q"+i,2,1,"other.md",2,1,.6);
                hits.printRecord("Q"+i,3,2,filename,3,0,.3);
            }
        }
        Files.writeString(dir.resolve("queries.csv"),q.toString());
        Files.writeString(dir.resolve("hits.csv"),h.toString());
        return dir;
    }
    @Test void loadsCsvEscapingAndNullsUsingSnapshotAndHashes() throws Exception {
        String name = "doc,\"quoted\"\nline.md";
        Path dir = fixture(name);
        var baseline = ThresholdBaseline.load(dir);
        assertThat(baseline.queries()).hasSize(15);
        assertThat(baseline.queries().getFirst().expectedDocument()).isEqualTo(name);
        assertThat(baseline.queries().getFirst().hits().get(2).filename()).isEqualTo(name);
        assertThat(baseline.hashes()).hasSize(3);
        assertThat(baseline.hashes().values()).allMatch(v -> v.matches("[a-f0-9]{64}"));
        String oldHash = baseline.hashes().get("summary.md");
        Files.writeString(dir.resolve("summary.md"),SUMMARY+"\n");
        assertThat(ThresholdBaseline.load(dir).hashes().get("summary.md")).isNotEqualTo(oldHash);
        assertThat(baseline.hashes().get("summary.md")).isEqualTo(oldHash);
    }
    @Test void topkSupportsLegacyAndRejectsIncompleteInput() throws Exception {
        Path dir=fixture("answer.md");
        Path output=temp.resolve("topk-output");
        assertThat(com.portfolio.ragobservability.evaluation.topk.TopKComparisonMain.run(new String[]{dir.toString(),output.toString(),"unavailable","unknown"})).isZero();
        Path report; try(var paths=Files.list(output)) { report=paths.findFirst().orElseThrow(); }
        assertThat(Files.readAllLines(report.resolve("query-comparison.csv"))).hasSize(61);
        Files.writeString(dir.resolve("summary.md"),SUMMARY.replace("COMPLETE","INCOMPLETE"));
        Path invalid=temp.resolve("invalid-topk");
        assertThat(com.portfolio.ragobservability.evaluation.topk.TopKComparisonMain.run(new String[]{dir.toString(),invalid.toString(),"unavailable","unknown"})).isEqualTo(1);
        assertThat(invalid).doesNotExist();
    }
    @Test void scoresThresholdBoundaryDuplicatesAndMissingAnswers() throws Exception {
        var baseline = ThresholdBaseline.load(fixture("answer.md"));
        var query = baseline.queries().getFirst();
        var atBoundary = ThresholdCalculator.score(query,.3);
        assertThat(atBoundary.rank()).isEqualTo(3);
        assertThat(atBoundary.rr()).isEqualTo(1.0/3);
        assertThat(ThresholdCalculator.score(query,.4).hit()).isFalse();
        assertThat(ThresholdCalculator.score(query,.4).resultCount()).isEqualTo(2);
        assertThat(ThresholdCalculator.score(query,.9).resultCount()).isZero();
        var first = new ThresholdBaseline.Query("FIRST","DIRECT","other.md", query.hits());
        assertThat(ThresholdCalculator.score(first,.3).rank()).isEqualTo(1);
        var unrelated = ThresholdCalculator.score(baseline.queries().getLast(),.3);
        assertThat(unrelated.hit()).isNull(); assertThat(unrelated.rr()).isNull();
    }
    @Test void aggregationUsesSeparateDenominatorsAndMonotonicResults() throws Exception {
        var comparisons = ThresholdCalculator.compare(ThresholdBaseline.load(fixture("answer.md")));
        assertThat(comparisons).hasSize(5);
        assertThat(comparisons.getFirst().hitRate()).isEqualTo(1);
        assertThat(comparisons.getFirst().mrr()).isCloseTo(1.0/3, within(1e-12));
        var at04 = comparisons.get(2);
        assertThat(at04.hitRate()).isZero(); assertThat(at04.relatedEmptyRate()).isZero();
        assertThat(at04.unrelatedReturnRate()).isEqualTo(1);
        assertThat(at04.relatedCount()).isEqualTo(12); assertThat(at04.unrelatedCount()).isEqualTo(3);
        assertThat(at04.lostAnswers()).hasSize(12);
        for (int i=1;i<comparisons.size();i++) for (int q=0;q<15;q++)
            assertThat(comparisons.get(i).scores().get(q).resultCount()).isLessThanOrEqualTo(comparisons.get(i-1).scores().get(q).resultCount());
    }
    @Test void malformedAndInconsistentInputsAreRejected() throws Exception {
        for (String mode : List.of("missing","duplicate","category","expected","failed","unknown","rank","count","nan","infinity","range","sort","chunk","column","metadata","incomplete","topk","score")) {
            Path dir = fixture("answer.md");
            Path file = dir.resolve("queries.csv"); String text = Files.readString(file);
            switch(mode) {
                case "missing" -> { Files.delete(dir.resolve("hits.csv")); }
                case "duplicate" -> text=text.replace("Q2,", "Q1,");
                case "category" -> text=text.replaceFirst("DIRECT", "UNKNOWN");
                case "expected" -> text=text.replaceFirst("DIRECT,answer.md", "DIRECT,");
                case "failed" -> text=text.replaceFirst("0.8,1.0,", "0.8,1.0,FAILED");
                case "count" -> text=text.replaceFirst("answer.md,3,3", "answer.md,4,3");
                case "column" -> text=text.replace("resultCount", "absent");
                case "score" -> text=text.replaceFirst("true", "false");
                case "metadata", "incomplete", "topk" -> {
                    file=dir.resolve("summary.md"); text=Files.readString(file);
                    text= switch(mode) { case "metadata" -> text.replace("- Dataset SHA-256:","- Missing:");
                        case "incomplete" -> text.replace("COMPLETE","INCOMPLETE"); default -> text.replace("5 chunks", "10 chunks"); };
                }
                default -> {
                    file=dir.resolve("hits.csv"); text=Files.readString(file);
                    text= switch(mode) {
                        case "unknown" -> text.replaceFirst("Q1,", "Q999,");
                        case "rank" -> text.replaceFirst("Q1,1,", "Q1,2,");
                        case "chunk" -> text.replace("other.md,2,1", "other.md,1,1");
                        case "sort" -> text.replaceFirst("0.8", "0.1");
                        case "nan" -> text.replaceFirst("0.8", "NaN");
                        case "infinity" -> text.replaceFirst("0.8", "Infinity");
                        default -> text.replaceFirst("0.8", "1.1");
                    };
                }
            }
            Files.writeString(file,text);
            assertThatThrownBy(() -> ThresholdBaseline.load(dir)).as(mode).isInstanceOf(java.io.IOException.class);
        }
    }
    @Test void reportsRoundTripCsvAndPreserveInputsAndPriorOutputs() throws Exception {
        Path dir=fixture("doc,\"quote\"\nline.md");
        var baseline=ThresholdBaseline.load(dir); var comparisons=ThresholdCalculator.compare(baseline);
        var report=new ThresholdReport();
        Path first=report.write(temp.resolve("out"),baseline,comparisons,"unavailable","true");
        String before=Files.readString(first.resolve("summary.md"));
        Path second=report.write(temp.resolve("out"),baseline,comparisons,"unavailable","true");
        assertThat(first).isNotEqualTo(second); assertThat(Files.readString(first.resolve("summary.md"))).isEqualTo(before);
        assertThat(ThresholdBaseline.load(dir).hashes()).isEqualTo(baseline.hashes());
        try (var parser=CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get().parse(Files.newBufferedReader(first.resolve("query-comparison.csv")))) {
            var rows=parser.getRecords(); assertThat(rows).hasSize(75);
            assertThat(rows.getFirst().get("expectedDocument")).isEqualTo("doc,\"quote\"\nline.md");
            assertThat(rows.get(14).get("hit")).isEmpty();
        }
        assertThat(before).contains("COMPLETE","Nonempty but wrong-document results", "Related denominator: 12", "Input SHA-256").doesNotContain("p50", "latencyMs");
    }
    @Test void cliFailureProducesNoSuccessReportAndSuccessReturnsZero() throws Exception {
        Path dir=fixture("answer.md"), out=temp.resolve("out");
        assertThat(ThresholdComparisonMain.run(new String[]{dir.toString(),out.toString(),"unavailable","unknown"})).isZero();
        Path failedOut=temp.resolve("failed"); Files.writeString(dir.resolve("summary.md"), "INCOMPLETE");
        assertThat(ThresholdComparisonMain.run(new String[]{dir.toString(),failedOut.toString(),"unavailable","unknown"})).isEqualTo(1);
        assertThat(Files.exists(failedOut)).isFalse();
        assertThat(ThresholdComparisonMain.run(new String[]{})).isEqualTo(1);
    }
    @Test void outputFailureReturnsNonzero() throws Exception {
        Path dir=fixture("answer.md"), file=temp.resolve("file"); Files.writeString(file,"existing");
        assertThat(ThresholdComparisonMain.run(new String[]{dir.toString(),file.toString(),"unavailable","unknown"})).isEqualTo(1);
        assertThat(Files.readString(file)).isEqualTo("existing");
    }
}
