package com.portfolio.ragobservability.rag;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.portfolio.ragobservability.retrieval.SearchHit;

@Component
public class ContextBuilder {

    private final AnswerProperties properties;

    public ContextBuilder(AnswerProperties properties) {
        this.properties = properties;
    }

    public BuiltContext build(List<SearchHit> hits) {
        StringBuilder context = new StringBuilder();
        List<RagSource> sources = new ArrayList<>(hits.size());
        for (int index = 0; index < hits.size(); index++) {
            SearchHit hit = hits.get(index);
            String sourceId = "S" + (index + 1);
            if (!context.isEmpty()) {
                context.append("\n\n");
            }
            context.append('[').append(sourceId).append("] document=")
                    .append(normalizeMetadata(hit.documentTitle()))
                    .append(", chunk=").append(hit.chunkIndex()).append('\n')
                    .append(hit.content());
            sources.add(new RagSource(
                    sourceId,
                    hit.chunkId(),
                    hit.documentId(),
                    hit.documentTitle(),
                    hit.chunkIndex(),
                    hit.similarity(),
                    excerpt(hit.content(), properties.getSourceExcerptLength())));
        }
        return new BuiltContext(context.toString(), sources);
    }

    private static String normalizeMetadata(String value) {
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    private static String excerpt(String content, int maximumCodePoints) {
        int codePoints = content.codePointCount(0, content.length());
        if (codePoints <= maximumCodePoints) {
            return content;
        }
        if (maximumCodePoints == 1) {
            return "…";
        }
        int end = content.offsetByCodePoints(0, maximumCodePoints - 1);
        return content.substring(0, end) + "…";
    }
}
