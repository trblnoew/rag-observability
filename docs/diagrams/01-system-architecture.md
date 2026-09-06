# 전체 System Architecture

~~~mermaid
flowchart LR
    subgraph Client["Client"]
        UI["React UI"]
        HTTP["HTTP Client"]
    end

    subgraph App["Spring Boot Application"]
        MVC["Spring MVC API"]

        subgraph Ingestion["Document ingestion · explicit step APIs"]
            DOC["Upload and document state"]
            SPLIT["Token chunking"]
            DEMB["Document embedding"]
        end

        subgraph Online["Online query"]
            RET["Retrieval · /api/v1/search"]
            RAG["RAG answer · /api/v1/answers"]
            CTX["Context and prompt"]
            GEN["Answer generation"]
            CITE["Citation validation"]
        end

        subgraph Evaluation["Offline retrieval evaluation"]
            GOLDEN["Golden dataset<br/>18 frozen queries"]
            EVAL["Evaluation runner"]
            REPORT["Queries · hits · summary<br/>manifest and hashes"]
            COMPARE["Threshold · Top-K<br/>Chunk-size comparison"]
        end

        TRACE["HTTP server span<br/>and RAG custom spans"]
    end

    PG[("PostgreSQL 17<br/>pgvector")]
    OAE["OpenAI Embedding API"]
    OAC["OpenAI Chat API"]
    COL["OpenTelemetry Collector"]
    TEMPO["Tempo"]
    GRAFANA["Grafana"]

    UI --> MVC
    HTTP --> MVC

    MVC --> DOC
    DOC --> SPLIT
    SPLIT --> PG
    DOC --> DEMB
    DEMB --> OAE
    DEMB --> PG

    MVC --> RET
    MVC --> RAG
    RAG --> RET
    RET --> OAE
    RET --> PG
    RAG --> CTX
    CTX --> GEN
    GEN -->|request| OAC
    OAC -->|answer| CITE

    GOLDEN --> EVAL
    EVAL -->|same retrieval path| RET
    EVAL --> REPORT --> COMPARE

    MVC -. automatic HTTP tracing .-> TRACE
    RET -. custom spans .-> TRACE
    RAG -. custom spans .-> TRACE
    TRACE -->|OTLP/HTTP| COL
    COL -->|OTLP/gRPC| TEMPO
    TEMPO --> GRAFANA
~~~

## 이 그림에서 면접관이 봐야 할 핵심 포인트

- 문서 적재는 Upload, Chunking, Embedding을 각각 호출하는 단계형 pipeline이고, 온라인 질의는 저장된 Vector를 사용한다.
- Golden Query 평가는 동일한 Retrieval 경로를 호출하고 결과·manifest·hash를 남겨, Generation 없이 설정별 검색 품질을 비교한다.
- Observability는 HTTP 자동 계측 위에 Retrieval/RAG Custom Span을 추가하고 Collector–Tempo–Grafana로 전달한다.

## 코드 근거

[DocumentController](../../src/main/java/com/portfolio/ragobservability/document/DocumentController.java) · [RetrievalController](../../src/main/java/com/portfolio/ragobservability/retrieval/RetrievalController.java) · [RagController](../../src/main/java/com/portfolio/ragobservability/rag/RagController.java) · [EvaluationRunner](../../src/main/java/com/portfolio/ragobservability/evaluation/EvaluationRunner.java) · [EmbeddingConfiguration](../../src/main/java/com/portfolio/ragobservability/document/embedding/EmbeddingConfiguration.java) · [GenerationConfiguration](../../src/main/java/com/portfolio/ragobservability/rag/GenerationConfiguration.java) · [Docker Compose](../../compose.yaml)
