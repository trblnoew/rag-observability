package com.portfolio.ragobservability.evaluation.chunksize;

import java.util.*;
import com.portfolio.ragobservability.document.embedding.*;
import com.portfolio.ragobservability.evaluation.GoldenDataset;

public final class QueryReplayGateway implements EmbeddingGateway {
    private final Map<String,float[]> vectors=new HashMap<>();
    private final String provider,model;
    private final int dimensions;
    int replayCalls;
    public QueryReplayGateway(EmbeddingGateway gateway, GoldenDataset data) {
        provider=gateway.provider(); model=gateway.model(); dimensions=gateway.dimensions();
        var validator=new EmbeddingVectorValidator();
        for (var q:data.queries()) {
            String text=q.question().strip();
            vectors.put(text,validator.validateResults(gateway.embed(List.of(text)),1,dimensions).getFirst().clone());
        }
    }
    public void verifyModel(EmbeddingGateway gateway) {
        if (!provider.equals(gateway.provider()) || !model.equals(gateway.model()) || dimensions!=gateway.dimensions())
            throw new IllegalStateException("QUERY_MODEL_MISMATCH");
    }
    public boolean isEnabled(){return true;}
    public String provider(){return provider;}
    public String model(){return model;}
    public int dimensions(){return dimensions;}
    public List<float[]> embed(List<String> input) {
        if (input.size()!=1 || !vectors.containsKey(input.getFirst())) throw new IllegalStateException("QUERY_CACHE_MISS");
        replayCalls++;
        return List.of(vectors.get(input.getFirst()).clone());
    }
}

