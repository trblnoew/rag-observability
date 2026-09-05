package com.portfolio.ragobservability.evaluation;

import java.nio.file.Path;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import com.portfolio.ragobservability.document.embedding.EmbeddingGateway;
import com.portfolio.ragobservability.retrieval.RetrievalService;

public final class EvaluationCli {
    private EvaluationCli() {}
    public static int execute(ConfigurableApplicationContext context) {
        var env = context.getEnvironment();
        if (!env.getProperty("rag.evaluation.live", Boolean.class, false)) {
            System.err.println("Evaluation requires scripts/run-evaluation.ps1 -Live");
            return 1;
        }
        try {
            return new EvaluationRunner(context.getBean(RetrievalService.class), context.getBean(EmbeddingGateway.class),
                    new EvaluationCorpus(context.getBean(JdbcClient.class)), new EvaluationReport())
                    .run(Path.of(env.getProperty("rag.evaluation.dataset-path", "evaluation/retrieval-golden-set.json")), Path.of("evaluation/results"),
                            env.getProperty("rag.evaluation.git-commit", "unavailable"),
                            env.getProperty("rag.evaluation.git-dirty", "unknown"));
        } catch (RuntimeException e) {
            System.err.println("EVALUATION_START_FAILED");
            return 1;
        }
    }
}
