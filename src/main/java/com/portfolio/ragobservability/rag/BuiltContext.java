package com.portfolio.ragobservability.rag;

import java.util.List;

public record BuiltContext(String content, List<RagSource> sources) {

    public BuiltContext {
        sources = List.copyOf(sources);
    }
}
