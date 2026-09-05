package com.portfolio.ragobservability.evaluation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

public record GoldenDataset(List<Query> queries, String sha256, String datasetId, int formatVersion,
                            java.util.Map<String, String> documents) {
    public static final Set<String> DOCUMENTS = Set.of(
            "db-connection-timeout.md", "cpu-spike.md", "deployment-rollback.md");
    public enum Category { DIRECT, PARAPHRASED, HARD_NEGATIVE, UNRELATED }
    public record Query(String queryId, Category category, String question,
                        String relevantDocument, String relevantChunkHint, String judgmentReason) {
        public boolean related() { return category == Category.DIRECT || category == Category.PARAPHRASED; }
    }
    public record Envelope(int formatVersion, String datasetId, java.util.Map<String, String> documents, List<Query> queries) {}

    public GoldenDataset { queries = List.copyOf(queries); documents = java.util.Map.copyOf(documents); }
    public static ObjectMapper mapper() {
        return new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    public static GoldenDataset load(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        GoldenDataset dataset;
        try {
            var tree = mapper().readTree(bytes);
            if (tree != null && tree.isArray()) {
                dataset = new GoldenDataset(java.util.Arrays.asList(mapper().treeToValue(tree, Query[].class)),
                        hash(bytes), "retrieval-golden-set-legacy", 1, java.util.Map.of());
            } else {
                Envelope envelope = mapper().treeToValue(tree, Envelope.class);
                if (envelope == null) throw new IOException("INVALID_DATASET");
                dataset = new GoldenDataset(envelope.queries(), hash(bytes), envelope.datasetId(), envelope.formatVersion(), envelope.documents());
                if (dataset.formatVersion() != 2) throw new IOException("INVALID_DATASET_VERSION");
            }
        } catch (IOException | RuntimeException e) { throw new IOException("INVALID_DATASET"); }
        dataset.validate();
        if (dataset.formatVersion() == 2) {
            var old = load(Path.of("evaluation/retrieval-golden-set.json"));
            var oldQuestions = old.queries().stream().map(q -> normalize(q.question())).collect(java.util.stream.Collectors.toSet());
            if (dataset.queries().stream().anyMatch(q -> oldQuestions.contains(normalize(q.question()))))
                throw new IOException("QUESTION_OVERLAPS_LEGACY");
        }
        return dataset;
    }

    public void validate() throws IOException {
        if (formatVersion != 1 && formatVersion != 2) throw new IOException("INVALID_DATASET_VERSION");
        if (datasetId == null || !datasetId.matches("[A-Za-z0-9_-]+") || sha256 == null || !sha256.matches("[a-f0-9]{64}"))
            throw new IOException("INVALID_DATASET_METADATA");
        if (formatVersion == 2 && (!documents.keySet().equals(DOCUMENTS)
                || documents.values().stream().anyMatch(v -> !v.matches("[a-f0-9]{64}")))) throw new IOException("INVALID_DOCUMENT_HASHES");
        if (queries.size() != (formatVersion == 1 ? 15 : 18)) throw new IOException("INVALID_QUERY_COUNT");
        Set<String> ids = new HashSet<>();
        Set<String> questions = new HashSet<>();
        for (Query q : queries) {
            if (q == null || blank(q.queryId()) || !q.queryId().matches("[A-Za-z0-9_-]+")
                    || !ids.add(q.queryId()) || q.category() == null || blank(q.question())
                    || q.question().strip().length() > 2000 || !questions.add(normalize(q.question()))) throw new IOException("INVALID_DATASET_ROW");
            if (formatVersion == 1 && q.category() == Category.HARD_NEGATIVE) throw new IOException("INVALID_LEGACY_CATEGORY");
            if (formatVersion == 2 && blank(q.judgmentReason())) throw new IOException("MISSING_JUDGMENT_REASON");
            if (!q.related()) {
                if (q.relevantDocument() != null || q.relevantChunkHint() != null)
                    throw new IOException("UNRELATED_MUST_HAVE_NO_EXPECTED_DOCUMENT");
            } else if (!DOCUMENTS.contains(q.relevantDocument() == null ? "" : q.relevantDocument())
                    || blank(q.relevantChunkHint())) throw new IOException("INVALID_EXPECTED_DOCUMENT");
        }
        for (String document : DOCUMENTS) for (Category category : List.of(Category.DIRECT, Category.PARAPHRASED)) {
            if (queries.stream().filter(q -> document.equals(q.relevantDocument()) && q.category() == category).count() != 2)
                throw new IOException("DATASET_REQUIRES_TWO_QUESTIONS_PER_CATEGORY_AND_DOCUMENT");
        }
        if (queries.stream().filter(q -> q.category() == Category.UNRELATED).count() != 3)
            throw new IOException("DATASET_REQUIRES_THREE_UNRELATED_QUESTIONS");
        if (formatVersion == 2 && queries.stream().filter(q -> q.category() == Category.HARD_NEGATIVE).count() != 3)
            throw new IOException("DATASET_REQUIRES_THREE_HARD_NEGATIVES");
    }
    public void validateLocalSources(Path sampleDirectory) throws IOException {
        for (var entry : documents.entrySet()) {
            if (!hash(Files.readAllBytes(sampleDirectory.resolve(entry.getKey()))).equals(entry.getValue()))
                throw new IOException("SOURCE_HASH_MISMATCH");
        }
    }
    public java.util.Map<String, Long> categoryCounts() {
        return queries.stream().collect(java.util.stream.Collectors.groupingBy(q -> q.category().name(), java.util.TreeMap::new, java.util.stream.Collectors.counting()));
    }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String normalize(String value) { return value.strip().replaceAll("(?U)\\s+", " ").toLowerCase(java.util.Locale.ROOT); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
