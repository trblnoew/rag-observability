package com.portfolio.ragobservability.observability;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class TraceHeaderFilter extends OncePerRequestFilter {
    private final RagTracing tracing;
    public TraceHeaderFilter(RagTracing tracing) { this.tracing = tracing; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return !path.equals("/api/v1/search") && !path.equals("/api/v1/answers");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        String id = tracing.currentTraceId();
        if (id != null) response.setHeader("X-Trace-Id", id);
        chain.doFilter(request, response);
    }
}
