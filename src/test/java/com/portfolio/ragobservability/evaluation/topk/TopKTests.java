package com.portfolio.ragobservability.evaluation.topk;

import static org.assertj.core.api.Assertions.*;
import static com.portfolio.ragobservability.evaluation.topk.TopKCalculator.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.commons.csv.*;
import com.portfolio.ragobservability.evaluation.threshold.ThresholdBaseline;

class TopKTests {
    @TempDir Path temp;
    ThresholdBaseline.Query query(int rank, String category) {
        var hits = new ArrayList<ThresholdBaseline.Hit>();
        for (int i=1;i<=5;i++) hits.add(new ThresholdBaseline.Hit(i==rank ? 2 : 1, i==rank ? "answer" : "other", i, i-1, i==5 ? .3 : .9-i*.1));
        return new ThresholdBaseline.Query("Q"+rank,category,"answer",hits,"a,\"b\"\nc","reason");
    }
    @Test void ranksBoundaryDuplicatesAndInvalidK() {
        for (int rank : List.of(1,2,3,5,6)) for (int k : KS) {
            var s=score(query(rank,"DIRECT"),k);
            assertThat(s.hit()).isEqualTo(rank<=k);
            assertThat(s.rr()).isEqualTo(rank<=k ? 1.0/rank : 0.0);
            assertThat(s.resultCount()).isEqualTo(k);
        }
        assertThat(score(query(3,"DIRECT"),3).documentCount()).isEqualTo(2);
        assertThatThrownBy(() -> score(query(1,"DIRECT"),10)).isInstanceOf(IllegalArgumentException.class);
        assertThat(score(query(1,"HARD_NEGATIVE"),5).rr()).isNull();
    }
    @Test void emptyAndShortLists() {
        var q=new ThresholdBaseline.Query("E","DIRECT","answer",List.of(new ThresholdBaseline.Hit(1,"answer",1,0,.2999)));
        assertThat(score(q,5).resultCount()).isZero();
        var one=new ThresholdBaseline.Query("S","DIRECT","answer",List.of(new ThresholdBaseline.Hit(1,"answer",1,0,.3)));
        assertThat(score(one,5).resultCount()).isEqualTo(1);
        assertThat(score(one,5).hit()).isTrue();
    }
    @Test void metricsAreMonotonicAndNegativeRatesConstant() {
        var b=new ThresholdBaseline(temp,Map.of(),Map.of(),List.of(query(2,"DIRECT"),query(3,"PARAPHRASED"),query(1,"HARD_NEGATIVE"),query(1,"UNRELATED")));
        double previous=0;
        for (var c:compare(b)) {
            assertThat(c.related()).hasSize(2); assertThat(c.negative()).hasSize(2);
            assertThat(hitRate(c.related())).isGreaterThanOrEqualTo(previous); previous=hitRate(c.related());
            assertThat(c.emptyRate()).isZero(); assertThat(returnRate(c.negative())).isEqualTo(1.0);
        }
    }
    @Test void reportEscapesCsvAndPreservesOutputs() throws Exception {
        var b=new ThresholdBaseline(temp,Map.of("hits.csv","hash"),Map.of(),List.of(query(2,"DIRECT"),query(1,"HARD_NEGATIVE")));
        Path first=new TopKReport().write(temp,b,"unavailable","unknown");
        String original=Files.readString(first.resolve("summary.md"));
        Path second=new TopKReport().write(temp,b,"unavailable","unknown");
        assertThat(second).isNotEqualTo(first);
        assertThat(Files.readString(first.resolve("summary.md"))).isEqualTo(original).contains("COMPLETE","Lost versus K=5: Q2");
        try(var reader=Files.newBufferedReader(first.resolve("query-comparison.csv")); var csv=CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get().parse(reader)) {
            var rows=csv.getRecords(); assertThat(rows).hasSize(8);
            assertThat(rows.getFirst().get("question")).isEqualTo("a,\"b\"\nc");
        }
        Path file=temp.resolve("file"); Files.writeString(file,"existing");
        assertThatThrownBy(() -> new TopKReport().write(file,b,"unavailable","unknown")).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(file)).isEqualTo("existing");
        assertThat(TopKComparisonMain.run(new String[]{})).isEqualTo(1);
        assertThat(TopKComparisonMain.run(new String[]{"missing",temp.toString(),"unavailable","unknown"})).isEqualTo(1);
    }
}
