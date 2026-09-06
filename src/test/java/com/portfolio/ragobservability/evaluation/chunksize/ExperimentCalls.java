package com.portfolio.ragobservability.evaluation.chunksize;
import java.util.List;
import com.portfolio.ragobservability.document.embedding.EmbeddingGateway;

public final class ExperimentCalls {
    public int queryAttempts,querySuccess,documentAttempts,documentSuccess;
    boolean queryPhase;
    public EmbeddingGateway wrap(EmbeddingGateway delegate) {
        return new EmbeddingGateway() {
            public boolean isEnabled(){return delegate.isEnabled();}
            public String provider(){return delegate.provider();}
            public String model(){return delegate.model();}
            public int dimensions(){return delegate.dimensions();}
            public List<float[]> embed(List<String> inputs) {
                if(queryPhase) queryAttempts++; else documentAttempts++;
                var result=delegate.embed(inputs);
                if(queryPhase) querySuccess++; else documentSuccess++;
                return result;
            }
        };
    }
}

