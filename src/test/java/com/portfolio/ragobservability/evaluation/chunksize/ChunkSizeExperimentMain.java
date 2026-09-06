package com.portfolio.ragobservability.evaluation.chunksize;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import com.portfolio.ragobservability.RagObservabilityApplication;
import com.portfolio.ragobservability.document.*;
import com.portfolio.ragobservability.document.chunk.*;
import com.portfolio.ragobservability.document.embedding.*;
import com.portfolio.ragobservability.evaluation.*;
import com.portfolio.ragobservability.retrieval.*;

/** Test-scope launcher; only explicit --live can construct an actual provider. */
public final class ChunkSizeExperimentMain {
    public static void main(String[] args) { System.exit(execute(args)); }
    static int execute(String[] args) {
        boolean live=Arrays.asList(args).contains("--live"), validate=Arrays.asList(args).contains("--validate-only");
        if(live && validate || Arrays.stream(args).anyMatch(a -> !Set.of("--live","--validate-only").contains(a))) return 1;
        try {
            var plan=ChunkSizePlan.load(); plan.print();
            if(!live) {System.out.println("Validation only: no Spring, DB or API calls. Use -Live explicitly.");return 0;}
            return run(plan,Path.of("evaluation/results"),null,System.getenv().getOrDefault("CHUNK_EXPERIMENT_GIT_COMMIT","unavailable"),
                    System.getenv().getOrDefault("CHUNK_EXPERIMENT_GIT_DIRTY","unknown"));
        } catch(Exception e) {System.err.println("CHUNK_EXPERIMENT_PREFLIGHT_FAILED");return 1;}
    }
    static int run(ChunkSizePlan plan,Path root,EmbeddingGateway fake,String commit,String dirty) throws Exception {
        var report=new ChunkSizeReport(root,commit,dirty);
        var calls=new ExperimentCalls();
        QueryReplayGateway replay=null;
        String provider=fake==null?"openai":fake.provider(),model=fake==null?"text-embedding-3-small":fake.model();
        boolean complete=false;
        try {
            for(int size:ChunkSizePlan.SIZES) {
                report.currentSize=size;report.stage="database";report.subject="";
                try(var postgres=new PostgreSQLContainer<>(DockerImageName.parse(ChunkSizePlan.IMAGE).asCompatibleSubstituteFor("postgres"))
                        .withReuse(false).withLabel("rag.chunk-experiment",report.directory.getFileName().toString())) {
                    postgres.start();
                    report.containers.add(postgres.getContainerId());
                    // Persist ownership before paid work, so interrupted runs remain identifiable.
                    report.save(plan,calls,false,provider,model,replay==null?0:replay.replayCalls);
                    try(var context=context(postgres,size,fake,calls)) {
                        JdbcClient jdbc=context.getBean(JdbcClient.class);
                        if(jdbc.sql("SELECT COUNT(*) FROM document").query(Long.class).single()!=0) throw new IllegalStateException("DATABASE_NOT_EMPTY");
                        var gateway=context.getBean(EmbeddingGateway.class);
                        if(!gateway.isEnabled() || gateway.dimensions()!=1536 || !gateway.provider().equals(provider) || !gateway.model().equals(model))
                            throw new IllegalStateException("MODEL_MISMATCH");
                        Map<String,Long> ids=new TreeMap<>();Map<Long,ChunkSizeReport.Chunk> chunkInfo=new HashMap<>();
                        var chunkRepo=context.getBean(DocumentChunkRepository.class);
                        // Finish local preparation and verify all stored splits before paid calls for this condition.
                        report.stage="chunking";
                        for(var source:plan.sources().entrySet()) {
                            report.subject=source.getKey();
                            long id=context.getBean(DocumentService.class).create(new MockMultipartFile("file",source.getKey(),"text/markdown",source.getValue()),null).id();
                            ids.put(source.getKey(),id);
                            context.getBean(DocumentProcessingService.class).process(id);
                            var stored=chunkRepo.findByDocumentId(id);var expected=plan.drafts().get(size).get(source.getKey());
                            if(stored.size()!=expected.size()) throw new IllegalStateException("CHUNK_MISMATCH");
                            for(int i=0;i<stored.size();i++) {
                                var c=stored.get(i);var d=expected.get(i);
                                if(c.chunkIndex()!=i || !c.content().equals(d.content()) || c.tokenCount()!=d.tokenCount()) throw new IllegalStateException("CHUNK_MISMATCH");
                                var info=new ChunkSizeReport.Chunk(size,source.getKey(),c.id(),i,GoldenDataset.hash(c.content().getBytes(StandardCharsets.UTF_8)),c.tokenCount());
                                report.chunks.add(info);chunkInfo.put(c.id(),info);
                            }
                        }
                        report.stage="query_embedding";report.subject="";
                        if(replay==null) {
                            calls.queryPhase=true;
                            // Query failures identify the ordinal without storing question text in errors.
                            replay=new QueryReplayGateway(gateway,plan.dataset());
                            calls.queryPhase=false;
                        }
                        replay.verifyModel(gateway);
                        report.stage="document_embedding";
                        for(var entry:ids.entrySet()) {
                            report.subject=entry.getKey();
                            context.getBean(EmbeddingService.class).process(entry.getValue());
                            report.save(plan,calls,false,provider,model,replay.replayCalls);
                        }
                        report.stage="corpus_validation";
                        var corpus=new EvaluationCorpus(jdbc).validate(gateway);
                        for(var d:corpus) if(!d.sha256().equals(plan.dataset().documents().get(d.filename()))) throw new IllegalStateException("CORPUS_HASH_MISMATCH");
                        var retrieval=new RetrievalService(replay,new EmbeddingVectorValidator(),context.getBean(RetrievalRepository.class),context.getBean(RetrievalProperties.class));
                        report.stage="search";
                        for(var q:plan.dataset().queries()) {
                            report.subject=q.queryId();
                            var result=retrieval.search(q.question(),5,.3).results();
                            int tokens=0;
                            for(int i=0;i<result.size();i++) {
                                var h=result.get(i);var c=chunkInfo.get(h.chunkId());
                                if(c==null) throw new IllegalStateException("CORPUS_CHANGED");
                                tokens+=c.tokens();
                                report.hits.add(new ChunkSizeReport.Hit(size,q.queryId(),i+1,c.filename(),h.chunkId(),h.chunkIndex(),h.similarity(),c.tokens()));
                            }
                            var score=EvaluationMetrics.score(q.related()?ids.get(q.relevantDocument()):null,result);
                            report.rows.add(new ChunkSizeReport.Row(size,q.queryId(),q.category().name(),q.relevantDocument(),score.firstRelevantRank(),score.hit(),
                                    score.reciprocalRank(),result.size(),(int)result.stream().map(SearchHit::documentId).distinct().count(),tokens));
                            report.save(plan,calls,false,provider,model,replay.replayCalls);
                        }
                    }
                }
                report.completedSizes.add(size);
            }
            complete=true; report.stage="complete";report.subject="";
        } catch(Exception e) {
            report.failureCode=safeCode(e);
            if(report.stage.equals("query_embedding")) report.subject=plan.dataset().queries().get(Math.max(0,Math.min(17,calls.queryAttempts-1))).queryId();
        } finally {
            report.save(plan,calls,complete,provider,model,replay==null?0:replay.replayCalls);
        }
        System.out.println("Chunk size experiment "+(complete?"COMPLETE":"INCOMPLETE")+": "+report.directory);
        return complete?0:1;
    }
    private static String safeCode(Exception e) {
        String code=e instanceof DocumentException d?d.code():e instanceof RetrievalException r?r.code():"EXPERIMENT_FAILED";
        return Set.of("EMBEDDING_DISABLED","EMBEDDING_PROVIDER_FAILED","EMBEDDING_INVALID_RESPONSE","EMBEDDING_PERSISTENCE_FAILED","RETRIEVAL_FAILED","CHUNKING_FAILED","CHUNK_LIMIT_EXCEEDED").contains(code)?code:"EXPERIMENT_FAILED";
    }
    private static ConfigurableApplicationContext context(PostgreSQLContainer<?> db,int size,EmbeddingGateway fake,ExperimentCalls calls) {
        Map<String,Object> overrides=new HashMap<>();
        overrides.put("spring.datasource.url",db.getJdbcUrl());overrides.put("spring.datasource.username",db.getUsername());overrides.put("spring.datasource.password",db.getPassword());
        overrides.put("spring.flyway.url",db.getJdbcUrl());overrides.put("spring.flyway.user",db.getUsername());overrides.put("spring.flyway.password",db.getPassword());
        overrides.put("spring.flyway.enabled","true");overrides.put("spring.flyway.locations","classpath:db/migration");
        overrides.put("spring.sql.init.mode","never");overrides.put("spring.main.web-application-type","none");
        overrides.put("spring.ai.model.chat","none");overrides.put("spring.ai.model.embedding","none");overrides.put("rag.generation.provider","none");
        overrides.put("rag.embedding.provider",fake==null?"openai":"none");overrides.put("rag.embedding.model","text-embedding-3-small");
        overrides.put("rag.embedding.dimensions","1536");overrides.put("rag.embedding.batch-size","32");
        overrides.put("rag.chunking.chunk-size-tokens",size);overrides.put("rag.chunking.max-chunks-per-document","500");
        overrides.put("management.tracing.enabled","false");overrides.put("logging.level.root","OFF");
        if(fake!=null) overrides.put("spring.config.import","");
        var app=new SpringApplication(RagObservabilityApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);app.setBannerMode(Banner.Mode.OFF);app.setLogStartupInfo(false);
        app.addInitializers(ctx -> {
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-experiment",overrides));
            ctx.addBeanFactoryPostProcessor(factory -> factory.addBeanPostProcessor(new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean,String name) {
                    return bean instanceof EmbeddingGateway g?calls.wrap(fake==null?g:fake):bean;
                }
            }));
        });
        return app.run();
    }
}

