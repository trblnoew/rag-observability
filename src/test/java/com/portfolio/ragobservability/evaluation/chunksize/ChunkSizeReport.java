package com.portfolio.ragobservability.evaluation.chunksize;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.apache.commons.csv.*;
import com.portfolio.ragobservability.evaluation.*;

public final class ChunkSizeReport {
    public record Chunk(int size,String filename,long id,int index,String hash,int tokens) {}
    public record Hit(int size,String queryId,int rank,String filename,long chunkId,int chunkIndex,double similarity,int tokens) {}
    public record Row(int size,String queryId,String category,String expectedDocument,Integer rank,Boolean hit,Double rr,int results,int documents,int tokens) {}
    public final List<Chunk> chunks=new ArrayList<>();
    public final List<Hit> hits=new ArrayList<>();
    public final List<Row> rows=new ArrayList<>();
    public final List<Integer> completedSizes=new ArrayList<>();
    public final List<String> containers=new ArrayList<>();
    public String failureCode="",stage="preflight",subject="";
    public int currentSize;
    public final Path directory;
    private final String started=Instant.now().toString();
    private final String commit,dirty;
    public ChunkSizeReport(Path root,String commit,String dirty) throws IOException {
        this.commit=commit;this.dirty=dirty;
        Files.createDirectories(root);
        directory=Files.createDirectory(root.resolve("chunk-size-comparison-"+started.replace(':','-')+"-"+UUID.randomUUID()));
        Files.writeString(directory.resolve("summary.md"),"# Chunk size comparison\n\nINCOMPLETE\n",StandardOpenOption.CREATE_NEW);
        Files.writeString(directory.resolve("run.json"),"{\"complete\":false}",StandardOpenOption.CREATE_NEW);
    }
    public void save(ChunkSizePlan plan,ExperimentCalls calls,boolean complete,String provider,String model,int replayCalls) throws IOException {
        writeCsv("chunks.csv",List.of("size","filename","chunkId","chunkIndex","sha256","tokenCount"),
                chunks.stream().map(c -> Arrays.<Object>asList(c.size(),c.filename(),c.id(),c.index(),c.hash(),c.tokens())).toList());
        writeCsv("hits.csv",List.of("size","queryId","rank","filename","chunkId","chunkIndex","similarity","tokenCount"),
                hits.stream().map(h -> Arrays.<Object>asList(h.size(),h.queryId(),h.rank(),h.filename(),h.chunkId(),h.chunkIndex(),h.similarity(),h.tokens())).toList());
        writeCsv("queries.csv",List.of("size","queryId","category","expectedDocument","firstRelevantRank","hit","rr","resultCount","documentCount","returnedTokens"),
                rows.stream().map(r -> Arrays.<Object>asList(r.size(),r.queryId(),r.category(),r.expectedDocument(),r.rank(),r.hit(),r.rr(),r.results(),r.documents(),r.tokens())).toList());
        List<List<Object>> aggregates=new ArrayList<>();
        StringBuilder md=new StringBuilder("# Chunk size comparison\n\n"+(complete?"COMPLETE":"INCOMPLETE")+"\n\n");
        md.append("Top-K=5; threshold=0.3; dimensions=1536; no generation; query vectors reused.\n\n");
        md.append("| Size | Hit@5 | MRR@5 | Negative return | Mean chunks | Mean returned tokens |\n|---|---|---|---|---|---|\n");
        if(complete) for(int size:ChunkSizePlan.SIZES) {
            var all=rows.stream().filter(r -> r.size()==size).toList();
            var related=all.stream().filter(r -> r.hit()!=null).toList();
            var negative=all.stream().filter(r -> r.hit()==null).toList();
            var direct=all.stream().filter(r -> r.category().equals("DIRECT")).toList();
            var para=all.stream().filter(r -> r.category().equals("PARAPHRASED")).toList();
            var hard=all.stream().filter(r -> r.category().equals("HARD_NEGATIVE")).toList();
            var none=all.stream().filter(r -> r.category().equals("UNRELATED")).toList();
            var stats=ChunkSizePlan.stats(chunks.stream().filter(c -> c.size()==size).map(Chunk::tokens).toList());
            double meanChunks=all.stream().mapToInt(Row::results).average().orElseThrow();
            double meanTokens=all.stream().mapToInt(Row::tokens).average().orElseThrow();
            aggregates.add(Arrays.asList(size,all.size(),related.size(),negative.size(),hit(related),mrr(related),
                    related.stream().filter(r -> r.results()==0).count()/(double)related.size(),returned(negative),
                    direct.size(),hit(direct),mrr(direct),para.size(),hit(para),mrr(para),hard.size(),returned(hard),none.size(),returned(none),
                    meanChunks,all.stream().mapToInt(Row::documents).average().orElseThrow(),meanTokens,
                    stats.count(),stats.min(),stats.mean(),stats.p50(),stats.p95(),stats.max(),stats.sum()));
            md.append("| ").append(size).append(" | ").append(f(hit(related))).append(" | ").append(f(mrr(related))).append(" | ")
              .append(f(returned(negative))).append(" | ").append(f(meanChunks)).append(" | ").append(f(meanTokens)).append(" |\n");
        }
        writeCsv("comparison.csv",List.of("size","totalCount","relatedCount","negativeCount","hitAt5","mrrAt5","relatedEmptyRate","negativeReturnRate",
                "directCount","directHit","directMrr","paraphrasedCount","paraphrasedHit","paraphrasedMrr","hardCount","hardReturnRate","unrelatedCount","unrelatedReturnRate",
                "meanChunks","meanDocuments","meanReturnedTokens","chunkCount","tokenMin","tokenMean","tokenP50","tokenP95","tokenMax","tokenSum"),aggregates);
        md.append("\n## Chunk token distribution\n\n| Size | Document | Count | Min | Mean | p50 | p95 | Max | Sum |\n|---|---|---|---|---|---|---|---|---|\n");
        Map<String,Object> statsByDocument=new TreeMap<>();
        plan.drafts().forEach((size,docs) -> docs.forEach((name,drafts) -> {
            var t=ChunkSizePlan.stats(drafts.stream().map(d -> d.tokenCount()).toList());
            statsByDocument.put(size+"/"+name,t);
            md.append("| ").append(size).append(" | ").append(name).append(" | ").append(t.count()).append(" | ").append(t.min()).append(" | ")
              .append(f(t.mean())).append(" | ").append(t.p50()).append(" | ").append(t.p95()).append(" | ").append(t.max()).append(" | ").append(t.sum()).append(" |\n");
        }));
        md.append("\nToken table describes frozen preflight splits; chunks.csv contains chunks actually stored.\n");
        if(complete) for(int size:ChunkSizePlan.SIZES) {
            md.append("\n### Size ").append(size).append(" versus 600\n\n");
            var baseRows=rows.stream().filter(r -> r.size()==600 && r.hit()!=null).toList();
            var here=rows.stream().filter(r -> r.size()==size && r.hit()!=null).toList();
            md.append("- Hit delta: ").append(f(hit(here)-hit(baseRows))).append("; MRR delta: ").append(f(mrr(here)-mrr(baseRows))).append('\n');
            md.append("- Lost answers: ").append(here.stream().filter(r -> !r.hit() && baseRows.stream().anyMatch(b -> b.queryId().equals(r.queryId()) && b.hit())).map(Row::queryId).toList()).append('\n');
            md.append("- Empty related: ").append(here.stream().filter(r -> r.results()==0).map(Row::queryId).toList()).append('\n');
            md.append("- Nonempty miss: ").append(here.stream().filter(r -> !r.hit() && r.results()>0).map(Row::queryId).toList()).append('\n');
        }
        md.append("\n## Limits\n\n문서 수준 정답 검색이며 근거 충분성·LLM 답변 정확성을 보장하지 않습니다.\n")
          .append("Token 통계는 추정치이며 실제 과금·Prompt 사용량이 아닙니다. 지연시간 비교는 하지 않습니다.\n")
          .append("새 600 결과와 과거 점수는 합산하지 않습니다. 운영 설정은 변경하지 않습니다.\n");
        if(!complete) md.append("\nFinal comparison not published. Failure: ").append(failureCode).append("; stage=").append(stage)
                .append("; size=").append(currentSize).append("; subject=").append(subject).append('\n');
        Map<String,Object> manifest=new LinkedHashMap<>();
        manifest.put("formatVersion",1);manifest.put("complete",complete);manifest.put("startedAt",started);
        manifest.put("dataset",plan.dataset());manifest.put("provider",provider);manifest.put("model",model);manifest.put("dimensions",1536);
        manifest.put("sizes",ChunkSizePlan.SIZES);manifest.put("topK",5);manifest.put("threshold",.3);manifest.put("batchSize",32);manifest.put("maxChunks",500);
        manifest.put("splitter","Spring AI 1.1.8 TokenTextSplitter; only chunkSize differs; defaults otherwise; no custom overlap");
        manifest.put("tokenEstimator","JTokkitTokenCountEstimator");manifest.put("image",ChunkSizePlan.IMAGE);
        manifest.put("gitCommit",commit);manifest.put("gitDirty",dirty);manifest.put("containers",containers);manifest.put("completedSizes",completedSizes);
        manifest.put("expectedQueryCalls",18);manifest.put("expectedDocumentCalls",plan.documentCalls());
        manifest.put("queryAttempts",calls.queryAttempts);manifest.put("querySucceeded",calls.querySuccess);
        manifest.put("documentAttempts",calls.documentAttempts);manifest.put("documentSucceeded",calls.documentSuccess);
        manifest.put("successfulProviderCallsMeaning","gateway returned; vector validation can still reject response");
        manifest.put("replaySearchCalls",replayCalls);manifest.put("generationCalls",0);
        manifest.put("failureCode",failureCode);manifest.put("stage",stage);manifest.put("size",currentSize);manifest.put("subject",subject);
        manifest.put("preflightTokenStats",statsByDocument);
        Map<String,String> hashes=new TreeMap<>();
        for(String name:List.of("comparison.csv","queries.csv","hits.csv","chunks.csv")) hashes.put(name,GoldenDataset.hash(Files.readAllBytes(directory.resolve(name))));
        manifest.put("fileHashes",hashes);
        Files.writeString(directory.resolve("run.pending"),GoldenDataset.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
        Files.move(directory.resolve("run.pending"),directory.resolve("run.json"),StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(directory.resolve("summary.pending"),md.toString());
        Files.move(directory.resolve("summary.pending"),directory.resolve("summary.md"),StandardCopyOption.REPLACE_EXISTING);
    }
    private void writeCsv(String name,List<String> header,List<List<Object>> rows) throws IOException {
        try(var writer=Files.newBufferedWriter(directory.resolve(name));var csv=new CSVPrinter(writer,CSVFormat.RFC4180)) {
            csv.printRecord(header);for(var row:rows) csv.printRecord(row);
        }
    }
    static double hit(List<Row> rows){return rows.stream().mapToDouble(r -> r.hit()?1:0).average().orElseThrow();}
    static double mrr(List<Row> rows){return rows.stream().mapToDouble(Row::rr).average().orElseThrow();}
    static double returned(List<Row> rows){return rows.stream().filter(r -> r.results()>0).count()/(double)rows.size();}
    private static String f(double n){return String.format(Locale.ROOT,"%.4f",n);}
}

