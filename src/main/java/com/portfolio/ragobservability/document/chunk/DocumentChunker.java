package com.portfolio.ragobservability.document.chunk;

import java.util.List;

public interface DocumentChunker {

    List<ChunkDraft> split(String content);
}
