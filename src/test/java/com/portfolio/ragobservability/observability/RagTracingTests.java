package com.portfolio.ragobservability.observability;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.Span;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;

class RagTracingTests {
    @Test void exceptionIsRethrownAndScopeAndSpanAreClosedWithSanitizedError() {
        Tracer tracer = mock(Tracer.class); Span span = mock(Span.class, RETURNS_SELF);
        var scope = mock(Tracer.SpanInScope.class);
        when(tracer.nextSpan()).thenReturn(span); when(tracer.withSpan(span)).thenReturn(scope);
        when(tracer.currentSpan()).thenReturn(span);
        var original = new RuntimeException("SECRET_PROVIDER");
        assertThatThrownBy(() -> new RagTracing(tracer).trace("rag.test", () -> { throw original; })).isSameAs(original);
        verify(scope).close(); verify(span).end();
        var error = org.mockito.ArgumentCaptor.forClass(Throwable.class);
        verify(span).error(error.capture());
        assertThat(error.getValue().getMessage()).isEqualTo("INTERNAL_ERROR");
        assertThat(error.getValue().getCause()).isNull();
        assertThat(error.getValue().getStackTrace()).isEmpty();
    }
    @Test void disabledOrAbsentTracerExecutesWorkWithoutHeader() throws Exception {
        @SuppressWarnings("unchecked") ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        var disabled = new RagTracing(provider, new MockEnvironment().withProperty("management.tracing.enabled", "false"));
        assertThat(disabled.trace("rag.test", () -> 42)).isEqualTo(42);
        verifyNoInteractions(provider);
        var absent = new RagTracing(provider, new MockEnvironment());
        assertThat(absent.trace("rag.test", () -> 42)).isEqualTo(42);
        var request = new MockHttpServletRequest("POST", "/api/v1/search"); request.setServletPath("/api/v1/search");
        var response = new MockHttpServletResponse();
        new TraceHeaderFilter(disabled).doFilter(request, response, new MockFilterChain());
        assertThat(response.getHeader("X-Trace-Id")).isNull();
    }
}
