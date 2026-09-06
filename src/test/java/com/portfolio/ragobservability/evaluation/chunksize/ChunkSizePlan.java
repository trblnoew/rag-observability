package com.portfolio.ragobservability.evaluation.chunksize;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.portfolio.ragobservability.evaluation.*;
import com.portfolio.ragobservability.document.chunk.*;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;

/** In-memory frozen inputs; never serialize this record (it contains source text). */
public record ChunkSizePlan(GoldenDataset dataset, Map<String,byte[]> sources, Map<Integer,Map<String,List<ChunkDraft>>> drafts) {
    public static final List<Integer> SIZES=List.of(300,600,900);
    public static final String IMAGE="pgvector/pgvector:0.8.6-pg17-trixie";
    public static ChunkSizePlan load() throws Exception {
        return load(Path.of("evaluation/retrieval-validation-set-v1.json"),Path.of("samples/retrieval-lab"));
    }
    static ChunkSizePlan load(Path datasetPath,Path sampleRoot) throws Exception {
        var data=GoldenDataset.load(datasetPath);
        if (!data.datasetId().equals("retrieval-validation-v1")) throw new IllegalStateException("INVALID_DATASET");
        Map<String,byte[]> sources=new TreeMap<>();
        for (var entry:data.documents().entrySet()) {
            byte[] bytes=Files.readAllBytes(sampleRoot.resolve(entry.getKey()));
            if (!GoldenDataset.hash(bytes).equals(entry.getValue())) throw new IllegalStateException("SOURCE_HASH_MISMATCH");
            sources.put(entry.getKey(),bytes);
        }
        Map<Integer,Map<String,List<ChunkDraft>>> drafts=new LinkedHashMap<>();
        for (int size:SIZES) {
            var chunker=new SpringAiDocumentChunker(TokenTextSplitter.builder().withChunkSize(size).build(),new JTokkitTokenCountEstimator());
            Map<String,List<ChunkDraft>> documents=new TreeMap<>();
            for (var entry:sources.entrySet()) {
                var chunks=chunker.split(new String(entry.getValue(),StandardCharsets.UTF_8));
                if (chunks.isEmpty() || chunks.size()>500 || chunks.stream().anyMatch(c -> c.content().isBlank() || c.tokenCount()<=0))
                    throw new IllegalStateException("INVALID_CHUNKS");
                documents.put(entry.getKey(),List.copyOf(chunks));
            }
            drafts.put(size,Collections.unmodifiableMap(documents));
        }
        return new ChunkSizePlan(data,Collections.unmodifiableMap(sources),Collections.unmodifiableMap(drafts));
    }
    public int documentCalls() { return drafts.values().stream().flatMap(m -> m.values().stream()).mapToInt(c -> (c.size()+31)/32).sum(); }
    public void print() {
        System.out.println("Dataset="+dataset.datasetId()+"; SHA-256="+dataset.sha256());
        drafts.forEach((size,docs) -> docs.forEach((name,chunks) -> System.out.println("Size="+size+"; file="+name+"; chunks="+chunks.size()+"; tokens="+stats(chunks.stream().map(ChunkDraft::tokenCount).toList())+"; batches="+((chunks.size()+31)/32))));
        System.out.println("Expected API calls: query=18; document="+documentCalls()+"; total="+(18+documentCalls())+"; generation=0; searches=54");
    }
    public record Stats(int count,int min,double mean,double p50,double p95,int max,long sum) {}
    public static Stats stats(List<Integer> values) {
        var sorted=values.stream().sorted().toList();
        return new Stats(values.size(),sorted.getFirst(),values.stream().mapToInt(i->i).average().orElseThrow(),
                sorted.get((int)Math.ceil(.5*values.size())-1),sorted.get((int)Math.ceil(.95*values.size())-1),
                sorted.getLast(),values.stream().mapToLong(i->i).sum());
    }
}
