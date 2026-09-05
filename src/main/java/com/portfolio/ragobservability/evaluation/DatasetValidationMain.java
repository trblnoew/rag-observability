package com.portfolio.ragobservability.evaluation;

import java.nio.file.Path;

/** Offline validation; no Spring context, .env, DB or AI client. */
public final class DatasetValidationMain {
    public static void main(String[] args) { System.exit(run(args)); }
    public static int run(String[] args) {
        if (args.length != 1) { System.err.println("Usage: DatasetValidationMain <datasetPath>"); return 1; }
        try {
            var dataset = GoldenDataset.load(Path.of(args[0]));
            dataset.validateLocalSources(Path.of("samples/retrieval-lab"));
            System.out.println("VALID: " + dataset.datasetId() + "; SHA-256=" + dataset.sha256());
            System.out.println("Questions=" + dataset.queries().size() + "; categories=" + dataset.categoryCounts());
            System.out.println("Expected live Query Embedding calls=" + dataset.queries().size() + "; Generation=0. No API calls made.");
            return 0;
        } catch (Exception e) {
            System.err.println("DATASET_VALIDATION_FAILED (check dataset structure, duplicate questions and source hashes)");
            return 1;
        }
    }
}
