package com.portfolio.ragobservability.evaluation.threshold;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;

/** Immutable snapshot: all scoring and provenance use the same bytes, never the current DB. */
public record ThresholdBaseline(Path source, Map<String, String> hashes, Map<String, String> metadata, List<Query> queries) {
    public record Hit(long documentId, String filename, long chunkId, int chunkIndex, double similarity) {}
    public record Query(String id, String category, String expectedDocument, List<Hit> hits, String question, String judgmentReason) {
        public Query(String id, String category, String expectedDocument, List<Hit> hits) { this(id, category, expectedDocument, hits, null, null); }
        public Query { hits = List.copyOf(hits); }
        public boolean related() { return category.equals("DIRECT") || category.equals("PARAPHRASED"); }
    }
    public ThresholdBaseline {
        hashes = Collections.unmodifiableMap(new LinkedHashMap<>(hashes));
        metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        queries = List.copyOf(queries);
    }
    private static final List<String> QUERY_COLUMNS = List.of("queryId", "category", "expectedDocument", "resultCount",
            "firstRelevantRank", "hit", "reciprocalRank", "topSimilarity", "latencyMs", "errorCode");
    private static final List<String> HIT_COLUMNS = List.of("queryId", "rank", "documentId", "originalFilename", "chunkId", "chunkIndex", "similarity");

    public static ThresholdBaseline load(Path directory) throws IOException {
        Path source = directory.toRealPath();
        Map<String, String> hashes = new LinkedHashMap<>();
        Map<String, String> files = new HashMap<>();
        for (String name : List.of("summary.md", "queries.csv", "hits.csv")) {
            byte[] bytes = Files.readAllBytes(source.resolve(name));
            hashes.put(name, sha256(bytes));
            try {
                files.put(name, StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString());
            } catch (java.nio.charset.CharacterCodingException e) { throw invalid("INVALID_UTF8"); }
        }
        com.portfolio.ragobservability.evaluation.RunManifest manifest = null;
        if (Files.exists(source.resolve("run.json"))) {
            manifest = com.portfolio.ragobservability.evaluation.RunManifest.read(source.resolve("run.json"), hashes);
            hashes.put("run.json", sha256(Files.readAllBytes(source.resolve("run.json"))));
        }
        int expectedCount = manifest == null ? 15 : manifest.dataset().queries().size();
        Map<String, String> metadata = parseSummary(files.get("summary.md"), expectedCount);
        if (manifest != null) {
            require(metadata.get("Dataset SHA-256").equals(manifest.dataset().sha256())
                    && metadata.get("Started (UTC)").equals(manifest.startedAt())
                    && metadata.get("Git commit").equals(manifest.gitCommit() + "; dirty: " + manifest.gitDirty())
                    && metadata.get("Provider/model/dimensions").equals(manifest.provider()+" / "+manifest.model()+" / "+manifest.dimensions())
                    && metadata.get("Corpus chunk-size provenance").equals(manifest.chunkSizeProvenance()), "MANIFEST_METADATA_MISMATCH");
            metadata.put("Dataset ID", manifest.dataset().datasetId());
            metadata.put("Category counts", manifest.categoryCounts().toString());
        }
        List<CSVRecord> queryRows = csv(files.get("queries.csv"), QUERY_COLUMNS);
        List<CSVRecord> hitRows = csv(files.get("hits.csv"), HIT_COLUMNS);
        require(queryRows.size() == expectedCount, "INVALID_QUERY_COUNT");
        Map<String, CSVRecord> queryMap = new LinkedHashMap<>();
        Map<String, List<Hit>> hits = new HashMap<>();
        int unrelated = 0;
        for (CSVRecord row : queryRows) {
            String id = row.get("queryId");
            require(id.matches("[A-Za-z0-9_-]+") && queryMap.putIfAbsent(id, row) == null, "INVALID_OR_DUPLICATE_QUERY_ID");
            String category = row.get("category");
            require((manifest == null ? Set.of("DIRECT", "PARAPHRASED", "UNRELATED")
                    : Set.of("DIRECT", "PARAPHRASED", "HARD_NEGATIVE", "UNRELATED")).contains(category), "INVALID_CATEGORY");
            if (category.equals("UNRELATED") || category.equals("HARD_NEGATIVE")) {
                unrelated++;
                require(row.get("expectedDocument").isEmpty(), "UNRELATED_HAS_EXPECTED_DOCUMENT");
            } else require(!row.get("expectedDocument").isBlank(), "MISSING_EXPECTED_DOCUMENT");
            require(row.get("errorCode").isEmpty(), "BASELINE_QUERY_FAILED");
            int count = integer(row.get("resultCount"));
            require(count >= 0 && count <= 5, "INVALID_RESULT_COUNT");
            double latency = number(row.get("latencyMs"));
            require(latency >= 0, "INVALID_LATENCY");
            hits.put(id, new ArrayList<>());
        }
        require(unrelated == (manifest == null ? 3 : manifest.categoryCounts().get("UNRELATED") + manifest.categoryCounts().get("HARD_NEGATIVE")), "NEGATIVE_COUNT_MISMATCH");
        Map<Long, String> filenames = new HashMap<>();
        Map<Long, Hit> chunks = new HashMap<>();
        Map<String, Set<Long>> seen = new HashMap<>();
        for (CSVRecord row : hitRows) {
            String id = row.get("queryId");
            require(hits.containsKey(id), "UNKNOWN_QUERY_ID");
            List<Hit> list = hits.get(id);
            require(integer(row.get("rank")) == list.size() + 1 && list.size() < 5, "INVALID_RANK");
            double similarity = number(row.get("similarity"));
            require(similarity >= -1 && similarity <= 1, "INVALID_SIMILARITY");
            require(list.isEmpty() || list.getLast().similarity() >= similarity, "UNSORTED_SIMILARITY");
            long document = positiveLong(row.get("documentId"));
            long chunk = positiveLong(row.get("chunkId"));
            int index = integer(row.get("chunkIndex"));
            String filename = row.get("originalFilename");
            require(index >= 0 && !filename.isBlank(), "INVALID_HIT_METADATA");
            require(seen.computeIfAbsent(id, ignored -> new HashSet<>()).add(chunk), "DUPLICATE_CHUNK_ID");
            String previousName = filenames.putIfAbsent(document, filename);
            require(previousName == null || previousName.equals(filename), "INCONSISTENT_DOCUMENT");
            Hit hit = new Hit(document, filename, chunk, index, similarity);
            Hit previous = chunks.putIfAbsent(chunk, hit);
            require(previous == null || (previous.documentId() == document && previous.chunkIndex() == index
                    && previous.filename().equals(filename)), "INCONSISTENT_CHUNK");
            list.add(hit);
        }
        List<Query> queries = new ArrayList<>();
        for (CSVRecord row : queryRows) {
            List<Hit> list = hits.get(row.get("queryId"));
            require(list.size() == integer(row.get("resultCount")), "RESULT_COUNT_MISMATCH");
            String top = row.get("topSimilarity");
            require(list.isEmpty() ? top.isEmpty() : !top.isEmpty() && number(top) == list.getFirst().similarity(), "TOP_SIMILARITY_MISMATCH");
            Query query = new Query(row.get("queryId"), row.get("category"), row.get("expectedDocument"), list);
            var score = ThresholdCalculator.score(query, -1.0);
            if (!query.related()) require(row.get("hit").isEmpty() && row.get("reciprocalRank").isEmpty()
                    && row.get("firstRelevantRank").isEmpty(), "UNRELATED_HAS_SCORE");
            else {
                require(row.get("hit").equals(score.hit().toString()), "BASELINE_SCORE_MISMATCH");
                require(Math.abs(number(row.get("reciprocalRank")) - score.rr()) < 1e-12, "BASELINE_SCORE_MISMATCH");
                require(score.rank() == null ? row.get("firstRelevantRank").isEmpty()
                        : integer(row.get("firstRelevantRank")) == score.rank(), "BASELINE_SCORE_MISMATCH");
            }
            if (manifest != null) {
                var expected = manifest.dataset().queries().get(queries.size());
                require(expected.queryId().equals(query.id()) && expected.category().name().equals(query.category())
                        && Objects.equals(expected.relevantDocument() == null ? "" : expected.relevantDocument(), query.expectedDocument()), "MANIFEST_QUERY_MISMATCH");
                query = new Query(query.id(), query.category(), query.expectedDocument(), list, expected.question(), expected.judgmentReason());
                for (Hit hit : list) require(manifest.documents().stream().anyMatch(d -> d.id()==hit.documentId()
                        && d.filename().equals(hit.filename()) && hit.chunkIndex()<d.chunkCount()), "MANIFEST_HIT_MISMATCH");
            }
            queries.add(query);
        }
        return new ThresholdBaseline(source, hashes, metadata, queries);
    }

    private static Map<String, String> parseSummary(String text, int count) throws IOException {
        List<String> lines = text.lines().toList();
        require(lines.stream().filter("COMPLETE"::equals).count() == 1 && !lines.contains("INCOMPLETE"), "BASELINE_NOT_COMPLETE");
        Map<String, String> meta = new LinkedHashMap<>();
        for (String key : List.of("Started (UTC)", "Git commit", "Dataset SHA-256", "Provider/model/dimensions",
                "Top-K", "Corpus chunk-size provenance", "Logical queries attempted")) {
            String prefix = "- " + key + ": ";
            List<String> found = lines.stream().filter(s -> s.startsWith(prefix)).toList();
            require(found.size() == 1 && !found.getFirst().substring(prefix.length()).isBlank(), "MISSING_OR_DUPLICATE_METADATA");
            meta.put(key, found.getFirst().substring(prefix.length()));
        }
        require(meta.get("Top-K").equals("5 chunks; minimum similarity: -1.0; cosine exact search"), "UNSUPPORTED_BASELINE_CONDITIONS");
        require(meta.get("Logical queries attempted").equals(count + " / " + count), "INCOMPLETE_QUERY_COUNT");
        require(meta.get("Dataset SHA-256").matches("[a-fA-F0-9]{64}"), "INVALID_DATASET_HASH");
        require(meta.get("Git commit").matches("([a-fA-F0-9]{40,64}|unavailable); dirty: (true|false|unknown)"), "INVALID_GIT_METADATA");
        require(meta.get("Provider/model/dimensions").matches("[^/]+ / [^/]+ / 1536"), "INVALID_MODEL_METADATA");
        try { java.time.Instant.parse(meta.get("Started (UTC)")); }
        catch (RuntimeException e) { throw invalid("INVALID_START_TIME"); }
        return meta;
    }
    private static List<CSVRecord> csv(String text, List<String> columns) throws IOException {
        var format = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true)
                .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).get();
        try (var parser = format.parse(new StringReader(text))) {
            require(parser.getHeaderNames().equals(columns), "INVALID_CSV_HEADER");
            List<CSVRecord> rows = parser.getRecords();
            for (CSVRecord row : rows) require(row.isConsistent(), "INVALID_CSV_ROW");
            return rows;
        } catch (IllegalArgumentException | java.io.UncheckedIOException e) { throw invalid("INVALID_CSV"); }
    }
    private static int integer(String value) throws IOException {
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { throw invalid("INVALID_INTEGER"); }
    }
    private static long positiveLong(String value) throws IOException {
        try { long n = Long.parseLong(value); require(n > 0, "INVALID_ID"); return n; }
        catch (NumberFormatException e) { throw invalid("INVALID_ID"); }
    }
    private static double number(String value) throws IOException {
        try { double n = Double.parseDouble(value); require(Double.isFinite(n), "NON_FINITE_NUMBER"); return n; }
        catch (NumberFormatException e) { throw invalid("INVALID_NUMBER"); }
    }
    private static void require(boolean condition, String code) throws IOException { if (!condition) throw invalid(code); }
    private static IOException invalid(String code) { return new InvalidBaselineException(code); }
    public static final class InvalidBaselineException extends IOException {
        public InvalidBaselineException(String code) { super(code); }
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
