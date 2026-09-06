# OpenTelemetry Trace and Span Flow

~~~mermaid
flowchart TD
    CLIENT["Client"]
    SERVER["HTTP SERVER span<br/>Trace ID"]
    HEADER["Response header<br/>X-Trace-Id = current Trace ID"]

    subgraph AnswerTrace["POST /api/v1/answers · normal custom span tree"]
        ANSWER["rag.answer"]
        RET["rag.retrieval"]
        QEMB["rag.query_embedding"]
        VSEARCH["rag.vector_search"]
        NOEV[/"rag.answer tags<br/>rag.outcome=no_evidence<br/>rag.generation.called=false"/]
        CTX["rag.context_build<br/>only with evidence"]
        GEN["rag.generation<br/>only with evidence"]
        CITE["rag.citation_validation<br/>only with evidence"]

        ANSWER --> RET
        ANSWER --> CTX
        ANSWER --> GEN
        ANSWER --> CITE
        RET --> QEMB
        RET --> VSEARCH
        RET -. empty result .-> NOEV
    end

    subgraph SearchTrace["POST /api/v1/search · custom span tree"]
        SRET["rag.retrieval"]
        SQEMB["rag.query_embedding"]
        SVSEARCH["rag.vector_search"]
        SRET --> SQEMB
        SRET --> SVSEARCH
    end

    OTLP["OTLP/HTTP exporter<br/>localhost:4318/v1/traces"]
    COL["OpenTelemetry Collector<br/>batch timeout 1 s"]
    TEMPO["Tempo<br/>retention 24 h"]
    GRAFANA["Grafana Explore<br/>lookup by Trace ID"]

    CLIENT --> SERVER
    SERVER -->|answers| ANSWER
    SERVER -->|search| SRET
    SERVER --> HEADER --> CLIENT

    SERVER -. finished spans .-> OTLP
    OTLP --> COL
    COL -->|OTLP/gRPC| TEMPO
    TEMPO --> GRAFANA
    CLIENT -. copy X-Trace-Id .-> GRAFANA
~~~

## 이 그림에서 면접관이 봐야 할 핵심 포인트

- HTTP server span 아래에 RAG 단계를 부모–자식 Span으로 배치해 Embedding, DB 검색, 생성 시간을 요청 단위로 분리한다.
- X-Trace-Id는 별도 UUID나 입력 propagation 값이 아니라 현재 server trace의 ID를 search/answers 응답에 복사한 값이다.
- 근거가 없으면 Generation 계열 Span이 생성되지 않으며, 현재 ingestion Custom Span과 metrics·logs pipeline은 없다.

## 코드 근거

[RagTracing](../../src/main/java/com/portfolio/ragobservability/observability/RagTracing.java) · [TraceHeaderFilter](../../src/main/java/com/portfolio/ragobservability/observability/TraceHeaderFilter.java) · [TracingHttpTests](../../src/test/java/com/portfolio/ragobservability/observability/TracingHttpTests.java) · [Collector config](../../infra/otel-collector/config.yaml) · [Tempo config](../../infra/tempo/tempo.yaml)
