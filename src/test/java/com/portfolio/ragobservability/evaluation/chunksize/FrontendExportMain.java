package com.portfolio.ragobservability.evaluation.chunksize;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.commons.csv.*;
import com.portfolio.ragobservability.evaluation.*;
import com.portfolio.ragobservability.evaluation.threshold.*;

/** Offline allowlist exporter. Source reports are never copied verbatim to the browser. */
public final class FrontendExportMain {
    public static void main(String[] args) throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("Expected thresholdDir topkDir chunkSizeDir outputDir");
        var exports=List.of(read(Path.of(args[0]),"threshold"),read(Path.of(args[1]),"topk"),read(Path.of(args[2]),"chunk"));
        Path out=Path.of(args[3]);Files.createDirectories(out);
        Files.writeString(out.resolve("experiments.json"),GoldenDataset.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(exports));
        var plan=ChunkSizePlan.load();List<Object> documents=new ArrayList<>();long id=0;
        for(var entry:plan.sources().entrySet()) {
            long documentId=++id;var text=new String(entry.getValue(),StandardCharsets.UTF_8);
            var doc=new LinkedHashMap<String,Object>();
            doc.put("id",documentId);doc.put("title",entry.getKey().replace(".md",""));doc.put("originalFilename",entry.getKey());doc.put("content",text);
            doc.put("contentSha256",plan.dataset().documents().get(entry.getKey()));doc.put("status","READY");doc.put("embeddingStatus","READY");
            doc.put("sourceType","MARKDOWN");doc.put("retryCount",0);doc.put("embeddingRetryCount",0);
            doc.put("createdAt","2026-09-05T00:00:00Z");doc.put("updatedAt","2026-09-05T00:00:00Z");
            List<Object> chunks=new ArrayList<>();int index=0;
            for(var draft:plan.drafts().get(600).get(entry.getKey())) {
                chunks.add(Map.of("id",documentId*100+index,"chunkIndex",index++,"content",draft.content(),"tokenCount",draft.tokenCount(),
                        "embedded",true,"embeddingProvider","demo","embeddingModel","illustration","createdAt","2026-09-05T00:00:00Z"));
            }
            doc.put("chunks",chunks);documents.add(doc);
        }
        Files.writeString(out.resolve("demo-documents.json"),GoldenDataset.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(documents));
        System.out.println("Validated three experiments; exported allowlisted data. API calls=0");
    }
    static Map<String,Object> read(Path dir,String kind) throws Exception {
        String summary=Files.readString(dir.resolve("summary.md"));
        check(summary.lines().anyMatch("COMPLETE"::equals)&&!summary.lines().anyMatch("INCOMPLETE"::equals));
        GoldenDataset data;String started;List<Map<String,String>> queryRows,hits;
        var aggregations=csv(dir.resolve("comparison.csv"));
        String condition=kind.equals("threshold")?"threshold":kind.equals("topk")?"topK":"size";
        String rankColumn=kind.equals("chunk")?"firstRelevantRank":"firstRelevantRank";
        if(kind.equals("chunk")) {
            var manifest=GoldenDataset.mapper().readTree(Files.readString(dir.resolve("run.json")));
            check(manifest.path("complete").asBoolean() && manifest.path("topK").asInt()==5 && manifest.path("threshold").asDouble()==.3
                    && manifest.path("provider").asText().equals("openai") && manifest.path("model").asText().equals("text-embedding-3-small")
                    && manifest.path("dimensions").asInt()==1536 && manifest.path("replaySearchCalls").asInt()==54);
            for(String name:List.of("comparison.csv","queries.csv","hits.csv","chunks.csv"))
                check(GoldenDataset.hash(Files.readAllBytes(dir.resolve(name))).equals(manifest.path("fileHashes").path(name).asText()));
            data=GoldenDataset.mapper().treeToValue(manifest.get("dataset"),GoldenDataset.class);data.validate();
            started=manifest.path("startedAt").asText();queryRows=csv(dir.resolve("queries.csv"));hits=csv(dir.resolve("hits.csv"));
        } else {
            String prefix=kind.equals("threshold")?"- Source baseline: ":"- Input: ";
            Path original=Path.of(line(summary,prefix));var baseline=ThresholdBaseline.load(original);
            String originalSummary=Files.readString(original.resolve("summary.md"));
            var manifest=GoldenDataset.mapper().readTree(Files.readString(original.resolve("run.json")));
            data=GoldenDataset.mapper().treeToValue(manifest.get("dataset"),GoldenDataset.class);data.validate();
            for(var e:baseline.hashes().entrySet()) {
                String mark=kind.equals("threshold")?"- Input SHA-256 ("+e.getKey()+"): ":"- SHA-256 "+e.getKey()+": ";
                check(line(summary,mark).equals(e.getValue()));
            }
            check(!baseline.queries().isEmpty());
            started=line(summary,kind.equals("threshold")?"- Comparison started (UTC): ":"- Comparison UTC: ");
            queryRows=csv(dir.resolve("query-comparison.csv"));hits=csv(original.resolve("hits.csv"));
        }
        check(data.datasetId().equals("retrieval-validation-v1") && data.queries().size()==18);
        java.time.Instant.parse(started);
        List<Double> conditions=aggregations.stream().map(r -> number(r.get(condition))).toList();
        check(new HashSet<>(conditions).size()==conditions.size());
        check(conditions.equals(kind.equals("chunk")?List.of(300.0,600.0,900.0):kind.equals("topk")?List.of(1.0,2.0,3.0,5.0):List.of(-1.0,.3,.4,.5)));
        check(queryRows.size()==conditions.size()*18);
        var exportedQueries=new ArrayList<Map<String,Object>>();var exportedRows=new ArrayList<Map<String,Object>>();
        for(double c:conditions) {
            var qs=queryRows.stream().filter(q -> number(q.get(condition))==c).toList();
            check(qs.size()==18&&qs.stream().map(q -> q.get("queryId")).distinct().count()==18);
            List<Boolean> successes=new ArrayList<>();double rr=0,returned=0,hard=0,none=0,direct=0,para=0,mean=0;
            for(var expected:data.queries()) {
                var q=qs.stream().filter(r -> r.get("queryId").equals(expected.queryId())).findFirst().orElseThrow();
                check(q.get("category").equals(expected.category().name()));
                String expectedColumn="expectedDocument";
                check(Objects.equals(q.get(expectedColumn),expected.relevantDocument()==null?"":expected.relevantDocument()));
                var hs=hits.stream().filter(h -> h.get("queryId").equals(expected.queryId())&&(!kind.equals("chunk")||number(h.get("size"))==c))
                        .sorted(Comparator.comparingInt(h -> Integer.parseInt(h.get("rank"))))
                        .filter(h -> number(h.get("similarity"))>=(kind.equals("threshold")?c:.3))
                        .limit(kind.equals("topk")?(int)c:5).toList();
                int rank=0;
                for(int i=0;i<hs.size();i++) if(expected.related() && hs.get(i).get(kind.equals("chunk")?"filename":"originalFilename").equals(expected.relevantDocument())){rank=i+1;break;}
                check(Integer.parseInt(q.get("resultCount"))==hs.size());
                check(q.get(rankColumn).equals(rank==0?"":Integer.toString(rank)));
                check(q.get("hit").equals(expected.related()?Boolean.toString(rank>0):""));
                if(expected.related()){successes.add(rank>0);rr+=rank==0?0:1.0/rank;if(rank>0){if(expected.category()==GoldenDataset.Category.DIRECT)direct++;else para++;}}
                else if(!hs.isEmpty()){returned++;if(expected.category()==GoldenDataset.Category.HARD_NEGATIVE)hard++;else none++;}
                mean+=hs.size();
                Map<String,Object> eq=new LinkedHashMap<>();eq.put("id",expected.queryId());eq.put("category",expected.category());eq.put("question",expected.question());
                eq.put("expected",expected.relevantDocument());eq.put("reason",expected.judgmentReason());eq.put("condition",c);
                eq.put("rank",rank==0?null:rank);eq.put("hit",expected.related()?rank>0:null);eq.put("resultCount",hs.size());exportedQueries.add(eq);
            }
            double hit=successes.stream().filter(b->b).count()/12.0;
            var agg=aggregations.stream().filter(r -> number(r.get(condition))==c).findFirst().orElseThrow();
            check(Math.abs(number(agg.get(kind.equals("topk")?"hitAtK":"hitAt5"))-hit)<1e-10);
            check(Math.abs(number(agg.get(kind.equals("topk")?"mrrAtK":"mrrAt5"))-rr/12)<1e-10);
            check(Math.abs(number(agg.get("negativeReturnRate"))-returned/6)<1e-10);
            check(Math.abs(number(agg.get(kind.equals("chunk")?"meanChunks":kind.equals("topk")?"meanChunkCount":"averageResultCount"))-mean/18)<1e-10);
            var exportedRow=new LinkedHashMap<String,Object>(Map.of("condition",c,"hit",hit,"mrr",rr/12,"negativeReturn",returned/6,"hardReturn",hard/3,
                    "unrelatedReturn",none/3,"meanChunks",mean/18,"directHit",direct/6,"paraphrasedHit",para/6));
            if(kind.equals("chunk")) {
                double tokens=qs.stream().mapToDouble(q -> number(q.get("returnedTokens"))).average().orElseThrow();
                var chunkRows=csv(dir.resolve("chunks.csv")).stream().filter(q -> number(q.get("size"))==c).toList();
                check(Math.abs(number(agg.get("meanReturnedTokens"))-tokens)<1e-10);
                check(number(agg.get("chunkCount"))==chunkRows.size());
                exportedRow.put("meanReturnedTokens",tokens);exportedRow.put("chunkCount",chunkRows.size());
            }
            exportedRows.add(exportedRow);
        }
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("kind",kind);result.put("title",kind.equals("threshold")?"Threshold":kind.equals("topk")?"Top-K":"Chunk Size");
        result.put("fixed",kind.equals("threshold")?"Chunk 600 · Top-K 5":kind.equals("topk")?"Chunk 600 · similarity 0.3":"Top-K 5 · similarity 0.3");
        result.put("runId",dir.getFileName().toString());result.put("startedAt",started);result.put("datasetId",data.datasetId());
        result.put("datasetHash",data.sha256());result.put("model","text-embedding-3-small / 1536");result.put("count",18);
        result.put("rows",exportedRows);result.put("queries",exportedQueries);return result;
    }
    static double number(String s){double n=Double.parseDouble(s);check(Double.isFinite(n));return n;}
    static String line(String text,String prefix){return text.lines().filter(l -> l.startsWith(prefix)).map(l -> l.substring(prefix.length())).findFirst().orElseThrow();}
    static void check(boolean value){if(!value)throw new IllegalArgumentException("INVALID_EXPERIMENT_EXPORT");}
    static List<Map<String,String>> csv(Path file)throws Exception{
        try(var r=Files.newBufferedReader(file);var p=CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).get().parse(r)){
            var rows=new ArrayList<Map<String,String>>();for(var record:p){check(record.isConsistent());rows.add(record.toMap());}return rows;
        }
    }
}
