package com.portfolio.ragobservability.evaluation.chunksize;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.portfolio.ragobservability.document.embedding.*;

class ChunkSizeUnitTests {
    @TempDir Path temp;
    static class Fake implements EmbeddingGateway {
        int calls,failAt;
        public boolean isEnabled(){return true;}
        public String provider(){return "fake";}
        public String model(){return "fake-1536";}
        public int dimensions(){return 1536;}
        public List<float[]> embed(List<String> inputs) {
            calls++; if(calls==failAt) throw new EmbeddingProviderException(new IllegalStateException("SECRET_PROVIDER_DETAIL"));
            return inputs.stream().map(t -> {float[] v=new float[1536];v[0]=1;return v;}).toList();
        }
    }
    @Test void preflightUsesRealSplitterAndCountsBatches() throws Exception {
        var p=ChunkSizePlan.load();var second=ChunkSizePlan.load();
        assertThat(p.dataset().queries()).hasSize(18);
        assertThat(p.drafts()).isEqualTo(second.drafts());
        assertThat(p.documentCalls()).isEqualTo(9);
        assertThat(p.drafts().get(300).values().stream().mapToInt(List::size).sum()).isEqualTo(25);
        assertThat(p.drafts().get(600).values().stream().mapToInt(List::size).sum()).isEqualTo(13);
        assertThat(p.drafts().get(900).values().stream().mapToInt(List::size).sum()).isEqualTo(9);
        assertThat(ChunkSizeExperimentMain.execute(new String[]{"--live","--validate-only"})).isEqualTo(1);
        assertThat(ChunkSizeExperimentMain.execute(new String[]{"--invalid"})).isEqualTo(1);
        assertThat(ChunkSizeExperimentMain.execute(new String[]{"--validate-only"})).isZero();
    }
    @Test void queryVectorsAreReplayedWithDefensiveCopiesAndNoFallback() throws Exception {
        var p=ChunkSizePlan.load();var fake=new Fake();var calls=new ExperimentCalls();calls.queryPhase=true;
        var replay=new QueryReplayGateway(calls.wrap(fake),p.dataset());
        assertThat(fake.calls).isEqualTo(18);
        for(int size:ChunkSizePlan.SIZES) for(var q:p.dataset().queries()) {
            var v=replay.embed(List.of(q.question().strip())).getFirst();assertThat(v[0]).isEqualTo(1);v[0]=99;
        }
        assertThat(replay.replayCalls).isEqualTo(54);assertThat(fake.calls).isEqualTo(18);
        assertThatThrownBy(() -> replay.embed(List.of("not cached"))).hasMessage("QUERY_CACHE_MISS");
        assertThatThrownBy(() -> replay.verifyModel(new Fake(){public String model(){return "different";}})).hasMessage("QUERY_MODEL_MISMATCH");
        assertThat(fake.calls).isEqualTo(18);
    }
    @Test void statisticsAndFailureReportAreSafeAndDirectoriesUnique() throws Exception {
        var t=ChunkSizePlan.stats(List.of(1,2,3,4,5));
        assertThat(t.p50()).isEqualTo(3);assertThat(t.p95()).isEqualTo(5);assertThat(t.sum()).isEqualTo(15);
        var p=ChunkSizePlan.load();var r=new ChunkSizeReport(temp,"unavailable","unknown");
        r.failureCode="EXPERIMENT_FAILED";r.save(p,new ExperimentCalls(),false,"fake","fake-1536",0);
        assertThat(Files.readString(r.directory.resolve("summary.md"))).contains("INCOMPLETE","not published");
        assertThat(Files.readAllLines(r.directory.resolve("comparison.csv"))).hasSize(1);
        assertThat(new ChunkSizeReport(temp,"unavailable","unknown").directory).isNotEqualTo(r.directory);
        Path file=temp.resolve("file");Files.writeString(file,"keep");
        assertThatThrownBy(() -> new ChunkSizeReport(file,"unavailable","unknown")).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(file)).isEqualTo("keep");
    }
    @Test void changedSourceIsRejectedAndMetricsUseOnlyRelatedRows() throws Exception {
        var p=ChunkSizePlan.load();
        for(var e:p.sources().entrySet()) Files.write(temp.resolve(e.getKey()),e.getValue());
        Files.writeString(temp.resolve("cpu-spike.md"),"changed");
        assertThatThrownBy(() -> ChunkSizePlan.load(Path.of("evaluation/retrieval-validation-set-v1.json"),temp)).hasMessage("SOURCE_HASH_MISMATCH");
        var rows=List.of(new ChunkSizeReport.Row(300,"A","DIRECT","a",1,true,1.0,2,1,100),
                new ChunkSizeReport.Row(300,"B","DIRECT","b",3,true,1.0/3,3,2,200),
                new ChunkSizeReport.Row(300,"C","DIRECT","c",null,false,0.0,0,0,0));
        assertThat(ChunkSizeReport.hit(rows)).isEqualTo(2.0/3);
        assertThat(ChunkSizeReport.mrr(rows)).isCloseTo(4.0/9,within(1e-12));
    }
}
