package com.portfolio.ragobservability.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AnswerPropertiesTests {

    @Test
    void usesDefaultsAndAcceptsBoundarySimilarity() {
        AnswerProperties properties = new AnswerProperties();
        properties.validate();
        assertThat(properties.getMinimumSimilarity()).isEqualTo(0.5);
        assertThat(properties.getSourceExcerptLength()).isEqualTo(500);

        properties.setMinimumSimilarity(-1.0);
        properties.validate();
        properties.setMinimumSimilarity(1.0);
        properties.validate();
    }

    @Test
    void rejectsInvalidValues() {
        AnswerProperties properties = new AnswerProperties();
        properties.setMinimumSimilarity(Double.NaN);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);

        properties = new AnswerProperties();
        properties.setSourceExcerptLength(0);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }
}
