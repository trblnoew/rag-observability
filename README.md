# RAG Observability

운영 장애 대응 문서(TXT/Markdown)를 검색 가능한 지식으로 변환하고, 질문에 맞는 근거를 찾아 답변하며, 그 전 과정을 하나의 Trace ID로 추적하는 RAG 백엔드입니다.

OpenAI는 Embedding·Chat model inference에 사용합니다. 그 전후의 문서 상태 전이, batch와 Vector 검증, pgvector 검색 SQL, 근거 제한과 인용 검사, Golden Query 평가, Custom Span과 Trace ID 연결은 이 저장소의 애플리케이션 코드가 담당합니다.

## 30초 요약

| 해결하려는 문제 | 구현한 내용 | 검증 근거 |
|---|---|---|
| 문서 전체를 LLM에 전달할 수 없고, 답변 근거를 선별해야 함 | Token 기반 Chunking → Embedding → pgvector 저장 → cosine distance 검색 | 실제 PostgreSQL을 사용하는 Testcontainers 통합 테스트 |
| 검색 결과가 그럴듯해 보여도 품질을 설명하기 어려움 | 18개 Golden Query, Hit@K·MRR·negative return rate, threshold·Top-K·Chunk size 비교 | 고정 데이터셋, 실행 manifest, 결과 hash, 오프라인 재채점 |
| API 지연이나 실패가 어느 단계에서 발생했는지 알기 어려움 | Retrieval·Embedding·DB Search·Generation·Citation Validation별 Span | Span 부모 관계·오류 태그·Tempo 전송 테스트 |
| 클라이언트 요청과 내부 Trace를 연결하기 어려움 | 응답의 X-Trace-Id로 현재 HTTP server trace를 노출 | HTTP 통합 테스트와 Grafana Tempo 조회 흐름 |

현재 구현 범위는 TXT/Markdown 문서와 exact vector search를 사용하는 단일 서비스입니다. ANN 인덱스, reranker, 답변 정확도 자동 평가는 아직 구현하지 않았습니다.

## 구현 경계

| 구분 | 담당 범위 |
|---|---|
| 이 저장소의 애플리케이션 코드 | 업로드 검증·SHA-256 중복 판정, Chunking/Embedding 상태와 트랜잭션, batch 결과·Vector 검증, pgvector SQL과 검색 적격 조건, Context/Prompt 조립과 citation 형식 검증, 평가 실행·report, RAG Custom Span과 `X-Trace-Id` |
| Library / framework | Spring MVC·JDBC·Flyway, Spring AI `TokenTextSplitter`, JTokkit token 추정, Spring Boot의 HTTP server span 자동 계측 |
| 외부 시스템 | OpenAI의 Embedding·Chat 추론, PostgreSQL/pgvector의 Vector 연산, Collector·Tempo·Grafana의 Trace 수집·저장·조회 |

대표 구현 진입점은 [EmbeddingService](src/main/java/com/portfolio/ragobservability/document/embedding/EmbeddingService.java), [RetrievalService](src/main/java/com/portfolio/ragobservability/retrieval/RetrievalService.java), [RetrievalRepository](src/main/java/com/portfolio/ragobservability/retrieval/RetrievalRepository.java), [RagService](src/main/java/com/portfolio/ragobservability/rag/RagService.java), [EvaluationRunner](src/main/java/com/portfolio/ragobservability/evaluation/EvaluationRunner.java), [RagTracing](src/main/java/com/portfolio/ragobservability/observability/RagTracing.java)입니다.

## 문제 정의

샘플 도메인은 DB connection timeout, CPU spike, deployment rollback에 관한 운영 문서 3개입니다. 목표는 이 문서에서 근거를 선별해 답변하는 작은 시스템을 만들고, 검색 결과와 요청 내부 과정을 코드·테스트·실험 결과로 설명 가능하게 만드는 것입니다.

LLM에 질문을 전달하는 것만으로는 다음 문제를 해결할 수 없습니다.

1. 어떤 문서를 어떤 크기로 나누고 벡터화할지 결정해야 합니다.
2. 질의 벡터와 같은 모델·차원의 문서 벡터만 안전하게 검색해야 합니다.
3. 검색 결과가 없을 때 생성을 생략하고, 답변이 실제 검색 근거를 인용하는지 확인해야 합니다.
4. 검색 설정을 감으로 고르지 않고 재현 가능한 데이터로 비교해야 합니다.
5. 한 요청 안에서 Embedding, DB 검색, LLM 생성 중 어디에서 시간이 들거나 실패했는지 추적해야 합니다.

이 저장소는 이 다섯 문제를 데이터 상태, SQL, 평가 도구, Trace 구조로 나누어 다룹니다. 현재 성공 조건은 3개 문서·18개 고정 질문에서 문서 단위 Hit/MRR와 negative return을 반복 측정할 수 있고, 통합 테스트에서 API 응답의 Trace ID로 내부 Span 계층을 확인할 수 있는 것입니다. 이 범위를 넘어서는 일반화 성능이나 운영 규모는 주장하지 않습니다.

## Architecture

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

문서 적재와 온라인 질의는 분리했습니다. 업로드 직후 외부 API를 자동 호출하지 않으며, Chunking과 Embedding을 각각 명시적인 API로 실행합니다. 이 구조 덕분에 각 단계의 상태와 실패 지점을 따로 확인하고 재시도할 수 있습니다.

상세 그림과 코드 근거: [전체 System Architecture](docs/diagrams/01-system-architecture.md)

## RAG pipeline

### 1. Document ingestion

<details>
<summary>Document ingestion diagram 펼치기</summary>

~~~mermaid
flowchart TD
    UP["POST /api/v1/documents<br/>TXT or Markdown · max 1 MB"]
    CHECK["Filename, extension, size,<br/>strict UTF-8 validation"]
    HASH["SHA-256 of original bytes<br/>BOM removed from stored text"]
    UNIQUE{"content_sha256<br/>already exists?"}
    DUP["409 DOCUMENT_DUPLICATE"]
    STORE[("document INSERT<br/>status=PENDING<br/>embedding_status=NOT_STARTED")]

    PROCESS["POST /documents/{id}/process"]
    CLAIM_C["Claim document<br/>status=PROCESSING"]
    SPLIT["TokenTextSplitter<br/>target 600 tokens"]
    VALID_C{"Valid chunks?<br/>1..500, nonblank"}
    COMPLETE_C["REQUIRES_NEW complete<br/>replace all chunks"]
    CHUNK_READY[("document_chunk rows<br/>document status=READY<br/>embedding_status=PENDING")]
    FAIL_C["REQUIRES_NEW fail<br/>delete partial chunks<br/>status=FAILED"]

    EMBED["POST /documents/{id}/embeddings"]
    CLAIM_E["Claim embedding work<br/>clear previous vectors<br/>embedding_status=PROCESSING"]
    BATCH["Chunk content batches<br/>default size 32"]
    OPENAI["Embedding provider"]
    VALID_E{"Count and 1536 dimensions<br/>finite and not all-zero"}
    COMPLETE_E["REQUIRES_NEW complete<br/>save all vectors and metadata"]
    READY[("embedding VECTOR(1536)<br/>provider and model<br/>embedding_status=READY")]
    FAIL_E["REQUIRES_NEW fail<br/>clear all vectors<br/>embedding_status=FAILED"]

    UP --> CHECK --> HASH --> UNIQUE
    UNIQUE -->|yes| DUP
    UNIQUE -->|no| STORE

    STORE -->|explicit API call| PROCESS
    PROCESS --> CLAIM_C --> SPLIT --> VALID_C
    VALID_C -->|yes| COMPLETE_C --> CHUNK_READY
    VALID_C -->|no| FAIL_C
    SPLIT -. exception .-> FAIL_C
    FAIL_C -->|retry process API| PROCESS

    CHUNK_READY -->|explicit API call| EMBED
    EMBED --> CLAIM_E --> BATCH --> OPENAI --> VALID_E
    VALID_E -->|all batches valid| COMPLETE_E --> READY
    VALID_E -->|invalid| FAIL_E
    OPENAI -. provider exception .-> FAIL_E
    FAIL_E -->|retry embedding API| EMBED
~~~

상세 그림과 코드 근거: [Document Ingestion Pipeline](docs/diagrams/02-document-ingestion-pipeline.md)

</details>

1. **Parsing:** [DocumentService](src/main/java/com/portfolio/ragobservability/document/DocumentService.java)는 최대 1MB의 TXT/Markdown 파일을 strict UTF-8로 읽습니다. 원본 byte의 SHA-256으로 중복 업로드를 막고, 저장할 문자열에서는 BOM을 제거합니다.

2. **Chunking:** [SpringAiDocumentChunker](src/main/java/com/portfolio/ragobservability/document/chunk/SpringAiDocumentChunker.java)는 Spring AI의 TokenTextSplitter와 JTokkit 추정기를 사용합니다. 기본 목표 크기는 600 token, 문서당 최대 Chunk 수는 500입니다. 별도의 사용자 정의 overlap 로직은 없습니다.

3. **상태 전이와 트랜잭션:** Chunking은 PENDING → PROCESSING → READY 또는 FAILED로 전이합니다. [DocumentProcessingTransactions](src/main/java/com/portfolio/ragobservability/document/chunk/DocumentProcessingTransactions.java)는 claim, 완료, 실패 처리를 REQUIRES_NEW 트랜잭션으로 분리하고, 실패 시 부분 Chunk를 제거합니다.

4. **Embedding:** [EmbeddingService](src/main/java/com/portfolio/ragobservability/document/embedding/EmbeddingService.java)는 기본 32개씩 batch 호출합니다. [EmbeddingVectorValidator](src/main/java/com/portfolio/ragobservability/document/embedding/EmbeddingVectorValidator.java)는 응답 개수, 1536차원, 유한값, all-zero 여부를 검사합니다. 모든 batch가 성공한 뒤 한 트랜잭션에서 벡터와 provider/model metadata를 저장합니다.

5. **Vector storage:** Flyway가 document와 document_chunk를 만들고 vector(1536) 컬럼을 관리합니다. 현재 vector ANN index는 없으며, 데이터 규모에 맞춰 exact scan부터 검증했습니다. 근거: [V1 migration](src/main/resources/db/migration/V1__create_document_schema.sql), [V2 migration](src/main/resources/db/migration/V2__add_document_raw_content.sql), [V3 migration](src/main/resources/db/migration/V3__add_document_embedding_state.sql)

### 2. Retrieval

<details>
<summary>RAG retrieval and answer diagram 펼치기</summary>

~~~mermaid
flowchart TD
    SEARCH["POST /api/v1/search"]
    ANSWER["POST /api/v1/answers"]

    VALID_S["Normalize query<br/>validate length, Top-K, threshold"]
    VALID_A["Normalize question<br/>validate length, Top-K, threshold"]
    GEN_ON{"Generation<br/>enabled?"}
    GEN_OFF["503 GENERATION_DISABLED"]

    EMB_ON{"Embedding<br/>enabled?"}
    EMB_OFF["503 EMBEDDING_DISABLED"]
    QEMB["Create one query embedding"]
    VVALID{"One valid vector?<br/>configured dimensions"}
    EMB_ERR["502 provider or<br/>validation error"]

    ELIGIBLE["Eligible chunks only<br/>document READY · embedding READY<br/>same provider, model, dimensions"]
    COSINE["Exact pgvector cosine search<br/>distance = embedding &lt;=&gt; query"]
    FILTER["similarity threshold<br/>deterministic ordering<br/>LIMIT Top-K"]

    SEARCH_OUT["Search response<br/>ranked chunks and metadata"]
    EVIDENCE{"Any evidence?"}
    NO_EVIDENCE["Fixed no-evidence answer<br/>generationCalled=false"]
    CONTEXT["Numbered context<br/>S1, S2, ..."]
    PROMPT["Prompt: use context only<br/>and cite source IDs"]
    CHAT["Chat generation"]
    CITE{"Citation syntax<br/>and ID range valid?"}
    CITE_ERR["502 GENERATION_INVALID_RESPONSE"]
    ANSWER_OUT["Answer response<br/>answer, sources, metadata"]

    SEARCH --> VALID_S --> EMB_ON
    ANSWER --> VALID_A --> GEN_ON
    GEN_ON -->|no| GEN_OFF
    GEN_ON -->|yes| EMB_ON

    EMB_ON -->|no| EMB_OFF
    EMB_ON -->|yes| QEMB --> VVALID
    QEMB -. provider error .-> EMB_ERR
    VVALID -->|no| EMB_ERR
    VVALID -->|yes| ELIGIBLE --> COSINE --> FILTER

    FILTER -->|search endpoint| SEARCH_OUT
    FILTER -->|answer endpoint| EVIDENCE
    EVIDENCE -->|no| NO_EVIDENCE
    EVIDENCE -->|yes| CONTEXT --> PROMPT --> CHAT --> CITE
    CITE -->|no| CITE_ERR
    CITE -->|yes| ANSWER_OUT
~~~

상세 그림과 코드 근거: [RAG Retrieval Pipeline](docs/diagrams/03-rag-retrieval-pipeline.md)

</details>

[RetrievalService](src/main/java/com/portfolio/ragobservability/retrieval/RetrievalService.java)는 질의를 정규화한 뒤 문서와 같은 Embedding Gateway로 query vector를 만듭니다. 검색 시 다음 조건을 모두 만족하는 Chunk만 후보가 됩니다.

- 문서의 Chunking 상태와 Embedding 상태가 모두 READY
- 벡터가 null이 아님
- 저장된 provider, model, dimensions가 현재 질의 설정과 일치
- cosine similarity가 minimumSimilarity 이상
- topK는 1~20 범위

핵심 검색은 추상화 뒤에 숨기지 않고 [RetrievalRepository](src/main/java/com/portfolio/ragobservability/retrieval/RetrievalRepository.java)에 SQL로 명시했습니다.

~~~sql
dc.embedding <=> CAST(:queryEmbedding AS vector) AS distance

WHERE d.status = 'READY'
  AND d.embedding_status = 'READY'
  AND dc.embedding_provider = :embeddingProvider
  AND dc.embedding_model = :embeddingModel
  AND vector_dims(dc.embedding) = :dimensions

-- cosine similarity = 1 - cosine distance
WHERE distance <= 1.0 - :minimumSimilarity
ORDER BY distance, document_id, chunk_index, chunk_id
LIMIT :topK
~~~

동점 결과도 반복 실행에서 같은 순서가 되도록 document_id, chunk_index, chunk_id까지 정렬합니다. 현재 검색에는 hybrid keyword search, metadata filter, reranking, 문서 단위 deduplication이 없습니다.

### 3. Answer generation

[RagService](src/main/java/com/portfolio/ragobservability/rag/RagService.java)는 검색 결과를 S1, S2 순서의 context로 구성합니다. [RagPromptBuilder](src/main/java/com/portfolio/ragobservability/rag/RagPromptBuilder.java)는 제공된 context만 사용하고 근거 ID를 인용하도록 지시합니다.

검색 결과가 없으면 LLM을 호출하지 않고 고정된 no-evidence 응답을 반환합니다. 결과가 있으면 [CitationValidator](src/main/java/com/portfolio/ragobservability/rag/CitationValidator.java)가 최소 한 개의 [Sn] 인용과 ID 범위를 검사합니다. 이 검사는 인용 형식 검증이며, 문장별 사실성이나 의미적 뒷받침까지 판정하지는 않습니다.

## Data model

<details>
<summary>Database relation diagram 펼치기</summary>

~~~mermaid
erDiagram
    DOCUMENT ||--o{ DOCUMENT_CHUNK : "ON DELETE CASCADE"

    DOCUMENT {
        bigint id PK
        varchar title
        varchar original_filename
        varchar source_type
        string content_sha256 UK
        text raw_content
        varchar status
        varchar error_code
        int retry_count
        varchar embedding_status
        varchar embedding_error_code
        int embedding_retry_count
        timestamptz created_at
        timestamptz updated_at
    }

    DOCUMENT_CHUNK {
        bigint id PK
        bigint document_id FK
        int chunk_index
        text content
        int token_count
        varchar embedding_provider
        varchar embedding_model
        vector_1536 embedding
        timestamptz created_at
    }
~~~

document와 document_chunk는 1:N 관계이며 문서 삭제 시 Chunk도 cascade 삭제됩니다. 원문 SHA-256과 문서별 chunk_index에는 unique 제약이 있고, Chunking 상태와 Embedding 상태·오류·retry 횟수는 분리되어 있습니다. 현재 B-tree index만 존재하며 Vector ANN index는 없습니다.

상세 그림과 코드 근거: [주요 DB 관계](docs/diagrams/05-database-relations.md)

</details>

## Observability

HTTP 자동 계측 위에 RAG 도메인 단계를 Custom Span으로 추가했습니다.

<details>
<summary>OpenTelemetry trace and span diagram 펼치기</summary>

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

상세 그림과 코드 근거: [OpenTelemetry Trace and Span Flow](docs/diagrams/04-opentelemetry-trace-flow.md)

</details>

검색 근거가 없으면 context_build, generation, citation_validation Span을 만들지 않고 rag.generation.called=false와 rag.outcome=no_evidence를 기록합니다. 오류에는 allowlist 기반의 안전한 rag.error.code만 남기며 질문, Chunk 전문, Prompt, Vector, provider 원문 오류는 Span attribute에 기록하지 않습니다.

구현은 [RagTracing](src/main/java/com/portfolio/ragobservability/observability/RagTracing.java), 구조 검증은 [RagTracingTests](src/test/java/com/portfolio/ragobservability/observability/RagTracingTests.java)와 [TracingHttpTests](src/test/java/com/portfolio/ragobservability/observability/TracingHttpTests.java)에 있습니다.

### X-Trace-Id

[TraceHeaderFilter](src/main/java/com/portfolio/ragobservability/observability/TraceHeaderFilter.java)는 search와 answers 요청에서 현재 HTTP server span의 Trace ID를 읽어 응답 헤더 X-Trace-Id에 넣습니다.

이 헤더는 별도의 UUID를 생성하지 않으며, 들어온 X-Trace-Id를 propagation header로 채택하지도 않습니다. Tracing이 비활성화되면 응답 헤더도 생성되지 않습니다.

Trace는 OTLP/HTTP → OpenTelemetry Collector → Tempo로 전달되고 Grafana에서 조회합니다. 현재 Compose 구성은 trace pipeline만 포함하며, metrics·logs pipeline, dashboard, alert는 구현 범위 밖입니다. 재현 절차는 [observability lab](docs/observability-lab.md), 실제 Trace ID·Span 수·검증 조건은 [2026-09-06 live path 검증](docs/evidence/observability-verification-2026-09-06.md)에 기록했습니다.

## 코드로 확인되는 문제 해결

| 문제 | 선택과 구현 | 확인 근거 |
|---|---|---|
| Embedding batch 중간 실패가 일부 Vector만 남길 수 있음 | 모든 batch의 개수·차원·값을 먼저 검증하고, 완료 시 한 트랜잭션에서 저장합니다. 실패 경로는 기존 Vector와 metadata를 지우고 Embedding 상태만 `FAILED`로 전환합니다. | [EmbeddingService](src/main/java/com/portfolio/ragobservability/document/embedding/EmbeddingService.java), [EmbeddingTransactions](src/main/java/com/portfolio/ragobservability/document/embedding/EmbeddingTransactions.java), [EmbeddingApiIntegrationTests](src/test/java/com/portfolio/ragobservability/document/embedding/EmbeddingApiIntegrationTests.java) |
| 다른 model·차원의 Vector가 검색 후보에 섞일 수 있음 | query Embedding의 provider/model/dimension을 검색 SQL의 적격 조건으로 사용하고, `READY` 문서와 non-null Vector만 대상으로 제한했습니다. | [RetrievalRepository](src/main/java/com/portfolio/ragobservability/retrieval/RetrievalRepository.java), [RetrievalApiIntegrationTests](src/test/java/com/portfolio/ragobservability/retrieval/RetrievalApiIntegrationTests.java) |
| 검색 근거가 없는데도 LLM을 호출하면 근거 없는 답과 비용이 생김 | Retrieval 결과가 비면 정해진 no-evidence 응답을 즉시 반환합니다. Generation·citation 검증은 호출하지 않고 Trace에도 해당 Span을 만들지 않습니다. | [RagService](src/main/java/com/portfolio/ragobservability/rag/RagService.java), [RagApiIntegrationTests](src/test/java/com/portfolio/ragobservability/rag/RagApiIntegrationTests.java), [TracingHttpTests](src/test/java/com/portfolio/ragobservability/observability/TracingHttpTests.java) |
| Similarity threshold를 높이면 오탐과 정답 손실이 함께 변함 | 같은 Top-5 결과를 threshold별로 재채점했습니다. 0.3은 관련 질문 Hit@5를 유지했지만 hard negative 3개를 모두 통과시켰고, 0.5는 관련 Hit@5가 0.3333으로 감소해 실험값으로 기본 설정을 자동 변경하지 않았습니다. | [threshold 비교 CSV](docs/evidence/threshold-comparison.csv), [threshold validation](docs/threshold-validation.md) |
| 실제 Collector 검증 테스트가 같은 Span을 두 번 OTLP 전송함 | Spring Boot auto exporter와 테스트 composite exporter의 중복을 확인했습니다. 테스트 설정에서 명시적 OTLP exporter와 in-memory exporter를 각각 한 번만 등록해 단독·전체 suite 동작을 맞췄습니다. | [TracingHttpTests](src/test/java/com/portfolio/ragobservability/observability/TracingHttpTests.java), [Trace 검증 기록](docs/evidence/observability-verification-2026-09-06.md) |

## Golden Query evaluation

검색 품질을 샘플 질문 몇 개의 눈대중으로 판단하지 않기 위해 평가를 코드와 데이터로 분리했습니다.

- [retrieval-validation-set-v1.json](evaluation/retrieval-validation-set-v1.json): direct 6, paraphrased 6, hard negative 3, unrelated 3의 총 18개 질문
- [GoldenDataset](src/main/java/com/portfolio/ragobservability/evaluation/GoldenDataset.java): schema, 중복, 원문 hash 검증
- [EvaluationCorpus](src/main/java/com/portfolio/ragobservability/evaluation/EvaluationCorpus.java): 정확히 3개 문서가 READY인지, 모델·차원과 원문 hash가 같은지 확인
- [EvaluationRunner](src/main/java/com/portfolio/ragobservability/evaluation/EvaluationRunner.java): 각 질문을 한 번 검색하고 query·hit·summary·manifest를 저장
- threshold와 Top-K 비교: 저장된 Top-5 결과를 오프라인에서 다시 채점하므로 추가 OpenAI 호출 없음
- Chunk size 비교: 300/600/900마다 새 PostgreSQL Testcontainer를 만들고 같은 query embedding을 재사용

지표는 정답 문서가 Top-K에 포함됐는지 보는 Hit@K와 첫 정답 순위를 보는 MRR@K입니다. 답이 없는 질문은 별도로 negativeReturnRate를 계산합니다. 현재 평가는 **문서 단위 검색 품질**만 측정하며 Chunk의 근거 충분성이나 최종 답변의 factuality는 측정하지 않습니다.

### 감사 시점의 로컬 결과

아래 값은 3개 문서와 18개 질문으로 수행한 내부 실험 결과입니다. 일반적인 RAG 성능을 주장하는 수치가 아닙니다. raw run 디렉터리 `evaluation/results`는 gitignore 대상이지만, 검토 가능한 집계 CSV와 run ID·dataset hash·source commit/dirty 상태는 [평가 증거와 재현 범위](docs/evidence/evaluation-provenance.md)에 별도로 보존했습니다.

| 조건 | 관련 질문 12개 | 답 없는 질문 6개 | 관찰 |
|---|---:|---:|---|
| Top-K 5, threshold -1.0 | Hit@5 1.0000, MRR@5 0.9167 | negative return 1.0000 | 모든 결과를 남긴 baseline |
| Top-K 5, threshold 0.3 | Hit@5 1.0000, MRR@5 0.9167 | negative return 0.5000 | 관련 검색은 유지했지만 hard negative는 모두 통과 |
| Top-K 5, threshold 0.5 | Hit@5 0.3333, MRR@5 0.3333 | negative return 0.1667 | 오탐은 줄었지만 관련 근거도 크게 손실 |
| threshold 0.3, K=1 | Hit@1 0.8333, MRR@1 0.8333 | negative return 0.5000 | 2개 관련 질문의 정답 문서를 놓침 |
| threshold 0.3, K=2/3/5 | Hit 1.0000, MRR 0.9167 | negative return 0.5000 | 문서 검색 성공률은 같지만 context 충분성은 별도 문제 |

Chunk size 300/600/900은 모두 Hit@5 1.0000이었지만 MRR과 반환 token 수가 달랐습니다. 이 결과만으로 최적 크기를 확정하지 않았고 운영 기본값 600, 답변 threshold 0.5도 자동 변경하지 않았습니다.

수치 원본 스냅샷은 [threshold CSV](docs/evidence/threshold-comparison.csv), [Top-K CSV](docs/evidence/topk-comparison.csv), [Chunk size CSV](docs/evidence/chunk-size-comparison.csv)에서 확인할 수 있습니다. 실험 설계와 제한은 [threshold validation](docs/threshold-validation.md), [Top-K validation](docs/topk-validation.md), [Chunk size validation](docs/chunk-size-validation.md)에 기록했습니다.

## 테스트와 검증

외부 API 자체보다 그 전후의 계약을 Fake Gateway와 실제 PostgreSQL로 검증합니다.

| 계층 | 검증 대상 | 대표 테스트 |
|---|---|---|
| Unit | UTF-8·hash, Chunk 경계, vector 검증, 상태 전이, citation, 평가 계산 | [SpringAiDocumentChunkerTests](src/test/java/com/portfolio/ragobservability/document/chunk/SpringAiDocumentChunkerTests.java), [EmbeddingServiceTests](src/test/java/com/portfolio/ragobservability/document/embedding/EmbeddingServiceTests.java), [ContextAndCitationTests](src/test/java/com/portfolio/ragobservability/rag/ContextAndCitationTests.java) |
| DB integration | Flyway V1~V3, vector 저장, cosine SQL, provider/model/dimension filter, rollback | [EmbeddingApiIntegrationTests](src/test/java/com/portfolio/ragobservability/document/embedding/EmbeddingApiIntegrationTests.java), [RetrievalApiIntegrationTests](src/test/java/com/portfolio/ragobservability/retrieval/RetrievalApiIntegrationTests.java) |
| HTTP integration | upload → chunk → embedding → search → answer, validation, status code | [DocumentApiIntegrationTests](src/test/java/com/portfolio/ragobservability/document/DocumentApiIntegrationTests.java), [RagApiIntegrationTests](src/test/java/com/portfolio/ragobservability/rag/RagApiIntegrationTests.java) |
| Observability | Span 계층, no-evidence branch, error code, X-Trace-Id, 선택적 Tempo 조회 | [TracingHttpTests](src/test/java/com/portfolio/ragobservability/observability/TracingHttpTests.java) |
| Evaluation | dataset 고정, metric 계산, manifest 무결성, threshold·Top-K·Chunk 비교 | [evaluation tests](src/test/java/com/portfolio/ragobservability/evaluation) |
| Frontend | API 상태 처리와 결과 렌더링 | [frontend tests](frontend/src) |

2026-09-06 로컬 감사 기준:

- Maven: 128개 중 124개 통과, 4개 skip, failure/error 0
- skip 4개: 실제 OpenAI 2개, 실제 Tempo 1개, opt-in browser integration 1개
- Testcontainers 기반 PostgreSQL 통합 테스트 실행
- Vitest: 16개 통과
- TypeScript 검사와 Vite production build 통과

실행 환경, 명령, dirty worktree 조건을 포함한 기록: [테스트 검증 기록](docs/evidence/test-verification-2026-09-06.md)

~~~powershell
.\mvnw.cmd clean test

Set-Location frontend
npm test
npm run build
~~~

## 주요 기술적 의사결정

| 결정 | 이유 | 현재 trade-off |
|---|---|---|
| VectorStore 추상화 대신 pgvector SQL 직접 작성 | cosine 계산, 적격 조건, 동점 정렬을 코드에서 명확히 통제 | DB 종속성이 생기지만 검색 계약을 테스트하기 쉬움 |
| ANN index보다 exact search 우선 | 3개 문서의 품질 실험에서 근사 검색 변수를 먼저 제거 | 대규모 데이터 성능은 아직 증명하지 못함 |
| Embedding과 Generation Gateway 분리 | 검색 평가에서 Generation 0회를 보장하고 provider 실패를 독립 처리 | 설정과 상태 모델이 늘어남 |
| Chunking과 Embedding 상태 분리 | 업로드 이후 각 단계를 관찰하고 실패 시 재시도 가능 | 현재 작업은 동기 API이며 queue·lease recovery는 없음 |
| AI provider 기본값 none | API key가 없는 로컬·테스트에서 외부 호출을 막음 | 실제 검색·답변 실행에는 명시적 설정 필요 |
| 원문 대신 안전한 Trace attribute만 기록 | 관측성과 데이터 노출 위험 사이의 경계 설정 | Trace만으로 질문 내용을 재현할 수 없음 |

결정 배경은 [ADR 0001](docs/adr/0001-use-direct-pgvector-sql.md), [ADR 0002](docs/adr/0002-start-with-exact-vector-search.md), [ADR 0003](docs/adr/0003-separate-embedding-and-generation-gateways.md), [ADR 0004](docs/adr/0004-pin-spring-boot-and-spring-ai-baseline.md)에 남겼습니다.

## 기술 스택

- Backend: Java 21, Spring Boot 3.5.16, Spring AI 1.1.8, Spring JDBC
- Data: PostgreSQL 17, pgvector 0.8.6, Flyway
- AI: OpenAI Embedding과 Chat Gateway
- Observability: Micrometer Tracing, OpenTelemetry, OTel Collector, Tempo, Grafana
- Test: JUnit 5, Spring Boot Test, Testcontainers, Fake Gateway
- UI: React 19.2, TypeScript 5.9, Vite 7.3, Vitest, Playwright
- Local infrastructure: Docker Compose

## 로컬 실행

요구 사항은 Java 21, Docker Desktop, Node.js입니다. PowerShell 기준 실행 순서입니다.

~~~powershell
Copy-Item .env.example .env
.\scripts\check-env.ps1
.\scripts\dev.ps1 up
.\scripts\run-app.ps1
~~~

- API health: http://localhost:8080/actuator/health
- Grafana: http://localhost:3000
- Tempo API: http://localhost:3200

기본 .env.example은 Embedding과 Chat provider가 none이므로 외부 AI 호출이 발생하지 않습니다. 실제 검색에는 SPRING_AI_MODEL_EMBEDDING=openai, 답변 생성에는 SPRING_AI_MODEL_CHAT=openai와 유효한 OPENAI_API_KEY가 필요합니다. 키는 커밋하지 않습니다.

Frontend는 별도 터미널에서 실행합니다.

~~~powershell
Set-Location frontend
npm ci
npm run dev
~~~

## 주요 API

| Method | Endpoint | 역할 |
|---|---|---|
| POST | /api/v1/documents | TXT/Markdown 업로드 |
| POST | /api/v1/documents/{id}/process | Chunking 실행 |
| POST | /api/v1/documents/{id}/embeddings | Chunk Embedding 실행 |
| GET | /api/v1/documents/{id}/chunks | Chunk와 embedding 여부 조회 |
| POST | /api/v1/search | query embedding과 vector search |
| POST | /api/v1/answers | Retrieval, context 구성, 생성, citation 검증 |

단계별 수동 검증 요청은 [http directory](http), 전체 흐름은 [retrieval lab](docs/retrieval-lab-results.md)에서 실행할 수 있습니다.

## 한계와 다음 과제

- 평가 corpus가 3개 문서, 18개 질문으로 작고 특정 운영 문서 도메인에 한정됩니다.
- relevantChunkHint는 데이터셋에 있지만 현재 평가는 document-level Hit/MRR만 계산합니다.
- 답변 factuality, citation entailment, LLM-as-a-judge 평가는 없습니다.
- hard negative를 충분히 제거하면서 관련 검색을 유지하는 threshold를 아직 찾지 못했습니다.
- exact scan만 사용하며 ANN index, hybrid search, reranker, metadata filter를 구현하지 않았습니다.
- TXT/Markdown만 지원하며 PDF, HTML, OCR은 지원하지 않습니다.
- 인증, 권한, tenant 분리, rate limit, 비동기 queue, DLQ가 없습니다.
- 처리 중 프로세스가 종료된 경우 PROCESSING 상태를 회수하는 lease/reaper가 없습니다.
- OpenTelemetry는 답변·검색 경로 중심이며 ingestion custom span, metrics, logs, dashboard, alert가 없습니다.
- CI/CD, Dockerfile 기반 애플리케이션 배포, 부하 테스트는 아직 없습니다.

## 더 자세한 문서

- [Repository 구현 감사](docs/PORTFOLIO_AUDIT.md)
- [포트폴리오 구성 계획](docs/PORTFOLIO_PLAN.md)
- [Frontend demo와 상태 구분](docs/frontend-demo.md)
- [RAG 실습 기록](docs/rag-lab-results.md)
- [Observability 실습](docs/observability-lab.md)
