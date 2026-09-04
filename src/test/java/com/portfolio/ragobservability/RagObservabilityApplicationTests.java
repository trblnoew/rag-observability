package com.portfolio.ragobservability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RagObservabilityApplicationTests {

    @Test
    void projectUsesJava21() {
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(21);
    }
}

