package com.portfolio.ragobservability.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RetrievalPropertiesTests {

    @Test
    void usesBaselineDefaults() {
        RetrievalProperties properties = new RetrievalProperties();

        assertThat(properties.getTopK()).isEqualTo(5);
        assertThat(properties.getMinimumSimilarity()).isZero();
    }

    @Test
    void rejectsInvalidTopKAndMinimumSimilarity() {
        RetrievalProperties zeroTopK = new RetrievalProperties();
        zeroTopK.setTopK(0);
        assertThatThrownBy(zeroTopK::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("top-k");

        RetrievalProperties tooLargeTopK = new RetrievalProperties();
        tooLargeTopK.setTopK(21);
        assertThatThrownBy(tooLargeTopK::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("top-k");

        for (double invalid : new double[] {-1.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            RetrievalProperties properties = new RetrievalProperties();
            properties.setMinimumSimilarity(invalid);
            assertThatThrownBy(properties::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("minimum-similarity");
        }
    }
}
