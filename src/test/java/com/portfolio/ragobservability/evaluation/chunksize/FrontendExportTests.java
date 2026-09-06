package com.portfolio.ragobservability.evaluation.chunksize;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.portfolio.ragobservability.evaluation.GoldenDataset;
import static org.assertj.core.api.Assertions.*;

class FrontendExportTests {
 @TempDir Path temp;
 private ChunkSizeReport fixture(boolean complete) throws Exception {
  var plan=ChunkSizePlan.load();var report=new ChunkSizeReport(temp,"test-commit","true");
  report.containers.add("PRIVATE_CONTAINER");
  for(int size:ChunkSizePlan.SIZES){
   for(var name:plan.dataset().documents().keySet()) report.chunks.add(new ChunkSizeReport.Chunk(size,name,1,0,"a".repeat(64),100));
   for(var q:plan.dataset().queries()){
    report.rows.add(new ChunkSizeReport.Row(size,q.queryId(),q.category().name(),q.relevantDocument(),q.related()?1:null,q.related()?true:null,q.related()?1.0:null,q.related()?1:0,q.related()?1:0,q.related()?100:0));
    if(q.related())report.hits.add(new ChunkSizeReport.Hit(size,q.queryId(),1,q.relevantDocument(),1,0,.8,100));
   }
  }
  report.save(plan,new ExperimentCalls(),complete,"openai","text-embedding-3-small",54);return report;
 }
 @Test void recomputesMetricsAndExportsOnlySafeFields() throws Exception {
  var report=fixture(true);var result=FrontendExportMain.read(report.directory,"chunk");
  var json=GoldenDataset.mapper().valueToTree(result);
  assertThat(json.get("queries")).hasSize(54);assertThat(json.get("rows")).hasSize(3);
  assertThat(json.get("rows").get(0).get("hit").asDouble()).isEqualTo(1);
  assertThat(json.get("rows").get(0).get("negativeReturn").asDouble()).isZero();
  assertThat(json.get("rows").get(0).get("meanReturnedTokens").asDouble()).isCloseTo(1200.0/18,within(1e-10));
  assertThat(json.toString()).doesNotContain("PRIVATE_CONTAINER","jdbc:",temp.toString(),"apiKey","content\"");
 }
 @Test void rejectsIncompleteAndTamperedReportsWithoutChangingSources() throws Exception {
  var incomplete=fixture(false);assertThatThrownBy(()->FrontendExportMain.read(incomplete.directory,"chunk")).isInstanceOf(IllegalArgumentException.class);
  var complete=fixture(true);var queries=complete.directory.resolve("queries.csv");Files.writeString(queries,"tampered");
  assertThatThrownBy(()->FrontendExportMain.read(complete.directory,"chunk")).isInstanceOf(IllegalArgumentException.class);
  assertThat(Files.readString(queries)).isEqualTo("tampered");
 }
 @Test void csvHandlesQuotesNewlinesAndRejectsDuplicateHeaders() throws Exception {
  var file=temp.resolve("quoted.csv");Files.writeString(file,"id,text\r\n1,\"a,\"\"b\"\"\nline\"\r\n");
  assertThat(FrontendExportMain.csv(file).getFirst().get("text")).isEqualTo("a,\"b\"\nline");
  Files.writeString(file,"id,id\n1,2\n");assertThatThrownBy(()->FrontendExportMain.csv(file)).isInstanceOf(IllegalArgumentException.class);
 }
}
