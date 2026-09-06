# RAG + OpenTelemetry 포트폴리오 프로젝트 감사

- 감사 기준일: 2026-09-06
- 감사 범위: 애플리케이션 코드, DB migration, 인프라 설정, 평가 데이터/도구, 백엔드·프론트엔드 테스트, 저장된 로컬 실험 산출물
- 원칙: 아래 내용은 현재 repository에서 확인한 구현만 기술한다. README나 수동 기록의 주장은 가능한 한 실제 코드·테스트·산출물과 대조했다.
- 주의: `evaluation/results/`와 일부 프론트엔드/문서는 현재 working tree에서 Git 추적 대상이 아니거나 변경 상태다. `evaluation/results/`는 `.gitignore`에 포함되지만, README에 인용한 비교 CSV와 run/dataset/source 정보는 `docs/evidence/`에 검토용 스냅샷으로 분리했다. 원본 run이 dirty worktree에서 생성됐다는 한계는 그대로 남는다.

## 감사 결론 요약

이 프로젝트는 운영 문서(TXT/Markdown)를 token 단위 Chunk와 OpenAI Embedding으로 변환해 PostgreSQL/pgvector에 저장하고, 질문을 동일한 임베딩 구성으로 벡터화하여 cosine exact search를 수행한 뒤, 검색 근거를 `[S1]` 형식의 인용 가능한 Context로 조립해 답변하는 로컬 포트폴리오 시스템이다. 검색·답변 요청에는 RAG 단계별 custom span을 만들고 응답의 `X-Trace-Id`로 Grafana Tempo의 trace를 찾을 수 있게 한다. 핵심 진입점은 `DocumentController`, `RetrievalController`, `RagController`이다.

강점은 다음 네 가지다.

1. Spring AI `VectorStore` 뒤에 검색 정책을 숨기지 않고, provider/model/dimension 적격성·cosine distance·threshold·tie-break를 직접 SQL로 표현했다. 근거: `RetrievalRepository.searchSimilar`, `docs/adr/0001-use-direct-pgvector-sql.md`, `docs/adr/0002-start-with-exact-vector-search.md`.
2. Chunking과 Embedding을 독립 상태 머신으로 관리하고, claim/complete/fail을 별도 transaction으로 분리해 실패 후에도 `FAILED` 상태와 안전한 오류를 보존한다. 근거: `DocumentProcessingTransactions`, `EmbeddingTransactions`, V1~V3 migration.
3. RAG가 근거 없을 때 Generation을 생략하며, 생성 답변의 source ID를 애플리케이션 경계에서 검증한다. 근거: `RagService.answerTraced`, `CitationValidator.validate`, `RagApiIntegrationTests.returnsNoEvidenceWithoutGenerationCall`.
4. 검색 품질 실험이 단순 데모에 머물지 않고 frozen dataset, corpus hash, model/dimension, Git 상태, input artifact hash를 기록하며 Hit@K/MRR/negative return을 분리한다. 근거: `GoldenDataset`, `EvaluationCorpus`, `EvaluationRunner`, `RunManifest`, threshold/top-k/chunk-size 비교 코드.

가장 큰 한계는 평가 corpus가 3개 문서·18개 validation query로 작고, 평가는 문서 수준 retrieval에 집중되어 있으며 답변의 faithfulness/근거 충분성은 자동 평가하지 않는다는 점이다. 또한 custom tracing 범위는 검색·답변에 집중되어 있고 ingestion/문서 Embedding, metrics/log correlation, 운영 보안·부하·대규모 ANN 검색은 아직 증명하지 않는다.

## 1. 프로젝트가 해결하려는 문제

### 실제로 해결하는 문제

운영 runbook처럼 길고 분산된 텍스트 문서에서 사용자의 자연어 질문과 관련된 근거를 찾아 답변하되, 단순한 LLM 응답이 아니라 다음을 함께 설명·검증할 수 있게 하는 것이 목적이다.

- 어떤 문서 Chunk가 검색되었는가: 검색 응답의 `SearchHit`와 답변 응답의 `AnswerSourceResponse`가 `documentId`, `chunkId`, `chunkIndex`, similarity를 노출한다. 근거: `retrieval/SearchHit.java`, `rag/dto/AnswerSourceResponse.java`.
- 왜 그 결과가 검색 가능한가: 직접 작성한 pgvector SQL이 상태, embedding provider/model/dimension, similarity threshold를 모두 검사한다. 근거: `RetrievalRepository.searchSimilar`.
- 근거가 부족할 때 불필요한 생성과 hallucination 위험을 어떻게 줄이는가: 검색 결과가 비면 고정 거절문을 반환하고 Generation을 호출하지 않는다. 근거: `RagService.NO_EVIDENCE_ANSWER`, `RagService.answerTraced`.
- 한 요청에서 어느 단계가 느리거나 실패했는가: query embedding, vector search, context build, generation, citation validation을 custom span으로 나눈다. 근거: `RagTracing`, `RetrievalService`, `RagService`.
- retrieval 설정의 품질 trade-off를 어떻게 재현하는가: frozen query와 실측 Top-5를 기반으로 threshold, Top-K, Chunk Size를 비교한다. 근거: `evaluation/`, `EvaluationRunner`, `ThresholdCalculator`, `TopKCalculator`, `ChunkSizeExperimentMain`.

### 해결 범위의 경계

이것은 범용 지식 검색 서비스가 아니라 로컬 운영 문서 corpus를 대상으로 한 evidence-first RAG 포트폴리오다. 인증, 다중 사용자/tenant, 공개 배포, 대규모 vector index, 자동 crawling, PDF/OCR은 구현되어 있지 않다. 지원 입력은 `.txt`와 `.md`의 UTF-8 텍스트뿐이다. 근거: `DocumentService.sourceTypeOf`, `DocumentSourceType`, `application.yml`, `docs/frontend-demo.md`의 제외 범위.

## 2. 전체 기술 스택

| 영역 | 실제 사용 기술 | 코드/설정 근거 |
|---|---|---|
| Language/runtime | Java 21 | `pom.xml`의 `java.version`, `RagObservabilityApplicationTests.projectUsesJava21` |
| Backend | Spring Boot 3.5.16, Spring MVC, Bean Validation | `pom.xml`, 세 Controller, request DTO validation annotation |
| AI integration | Spring AI 1.1.8, OpenAI chat/embedding adapter | `pom.xml`, `EmbeddingConfiguration`, `GenerationConfiguration` |
| Persistence | Spring JDBC `JdbcClient`/`NamedParameterJdbcTemplate` | 각 Repository |
| Database | PostgreSQL 17 + pgvector 0.8.6 | `compose.yaml`, V1 migration |
| Migration | Flyway | `pom.xml`, `application.yml`, V1~V3 SQL |
| Tracing | Spring Actuator/Micrometer Tracing → OpenTelemetry bridge → OTLP exporter | `pom.xml`, `application.yml`, `RagTracing` |
| Trace pipeline | OTel Collector 0.159.0 → Tempo 2.10.8 → Grafana 13.1.0 | `compose.yaml`, `infra/otel-collector/config.yaml`, `infra/tempo/tempo.yaml`, Grafana datasource |
| Evaluation | Plain Java CLI, Jackson, Apache Commons CSV | `evaluation` package, `commons-csv` dependency |
| Backend test | JUnit 5, AssertJ/Mockito, MockMvc/TestRestTemplate, Testcontainers PostgreSQL, OTel in-memory exporter | `pom.xml`, `src/test/java` |
| Frontend | React 19.2, TypeScript 5.9, Vite 7.3 | `frontend/package.json` |
| Frontend test | Vitest 3.2, Playwright 1.58 with Edge channel | `frontend/package.json`, Playwright configs |
| Local orchestration | Docker Compose, PowerShell scripts | `compose.yaml`, `scripts/` |

Spring AI의 사용 범위는 모델 호출과 token-based splitter/token estimator다. Vector persistence와 retrieval에는 Spring AI `VectorStore`를 사용하지 않는다. 근거: `SpringAiDocumentChunker`, `OpenAiEmbeddingGateway`, `OpenAiGenerationGateway`, `RetrievalRepository`, ADR-0001.

## 3. 주요 architecture

### 논리 계층

```text
React/Vite UI 또는 HTTP client
          |
          v
Spring MVC Controller
  DocumentController | RetrievalController | RagController
          |
          v
Application Service
  DocumentService
  DocumentProcessingService -> DocumentProcessingTransactions
  EmbeddingService          -> EmbeddingTransactions
  RetrievalService
  RagService
          |
     +----+----------------------+------------------+
     |                           |                  |
     v                           v                  v
Spring AI adapters          JDBC repositories   RAG assembly
EmbeddingGateway            DocumentRepository  ContextBuilder
GenerationGateway           Chunk/Embedding     RagPromptBuilder
                            RetrievalRepository CitationValidator
     |                           |
     v                           v
OpenAI API                 PostgreSQL + pgvector

HTTP server span -> custom rag.* spans -> OTLP/HTTP
                                      -> OTel Collector -> Tempo -> Grafana
```

### 설계 경계

- Controller는 request validation과 DTO 변환만 담당한다. 핵심 흐름은 Service에 있다. 근거: 세 Controller.
- Provider SDK 타입은 adapter 내부에 머문다. 도메인 서비스는 `EmbeddingGateway`, `GenerationGateway`와 자체 record만 사용한다. 근거: 두 Gateway interface와 두 OpenAI adapter, ADR-0003.
- pgvector 연산은 Repository에 집중된다. vector literal 직렬화는 `PgVectorFormatter`가 담당한다.
- 장시간 외부 Embedding 호출을 DB transaction 안에 묶지 않는다. 상태 claim, 외부 호출, complete/fail이 분리된다. 근거: `EmbeddingService.process`, `EmbeddingTransactions`의 `REQUIRES_NEW`.
- 기본 provider는 `none`이므로 API key 없이 부팅 가능하되 AI endpoint는 503을 반환한다. 근거: `application.yml`, `EmbeddingConfiguration.disabledEmbeddingGateway`, `GenerationConfiguration.disabledGenerationGateway`.

### 배포 형태

Spring Boot 애플리케이션 자체는 host에서 실행하며, PostgreSQL/Collector/Tempo/Grafana는 Compose service다. repository에는 애플리케이션용 Dockerfile이나 Kubernetes manifest가 없다. 프론트엔드는 Vite 개발 서버가 `/api`와 health만 `localhost:8080`으로 proxy한다. 근거: `compose.yaml`, `frontend/vite.config.ts`.

## 4. 요청이 들어온 뒤 응답이 나갈 때까지의 data flow

### 검색 요청: `POST /api/v1/search`

1. Spring MVC가 JSON을 `SearchRequest`로 역직렬화하고 `@NotBlank`, Top-K 1~20, similarity -1~1을 검증한다. unknown JSON field도 거부한다. 근거: `SearchRequest`, `application.yml`의 `fail-on-unknown-properties`, `RetrievalApiIntegrationTests.validatesRequestAndRejectsUnknownFields`.
2. 자동 HTTP server span이 활성화된 상태에서 `TraceHeaderFilter`가 현재 trace ID를 읽어 `X-Trace-Id` response header에 설정한다.
3. `RetrievalController.search`가 `RetrievalService.search`를 호출한다.
4. `rag.retrieval` span에서 질문 앞뒤 공백을 제거하고 request override 또는 기본 Top-K/threshold를 확정한다.
5. `rag.query_embedding` span에서 `EmbeddingGateway.embed(List.of(query))`를 호출하고 반환 개수·1536차원·finite·non-zero 조건을 검증한다.
6. `rag.vector_search` span에서 `RetrievalRepository.searchSimilar`가 exact cosine search를 실행한다.
7. `SearchResponse.from`이 query, ordered hits, 적용 설정과 embedding metadata를 반환한다. 빈 검색도 정상 `200`이다.

근거: `RetrievalController`, `RetrievalService.searchTraced`, `EmbeddingVectorValidator`, `RetrievalRepository`, `SearchResponse`.

### 답변 요청: `POST /api/v1/answers`

1. `AnswerRequest` validation 후 `RagController.answer`가 `RagService.answer`를 호출한다.
2. `rag.answer` span에서 질문과 override를 검증하고 Generation provider가 활성화됐는지 먼저 확인한다. 즉 Generation이 disabled이면 retrieval도 실행하지 않는다. 근거: `RagService.answerTraced`, `RagServiceTests.rejectsDisabledGenerationBeforeRetrieval`.
3. 위 검색 흐름 전체가 `rag.retrieval` child span으로 실행된다. 답변용 default threshold는 retrieval 기본 0.0이 아니라 `rag.answer.minimum-similarity=0.5`다.
4. hit가 없으면 `rag.outcome=no_evidence`, `rag.generation.called=false`를 남기고 고정문과 빈 sources, null token usage를 반환한다.
5. hit가 있으면 `rag.context_build`에서 검색 순서를 그대로 S1..Sn에 매핑하고 Context와 외부 응답용 excerpt를 만든다.
6. `RagPromptBuilder`가 system prompt와 question/context가 들어간 user prompt를 분리한다. Context 내부 명령을 신뢰하지 말라는 규칙과 source citation 규칙이 system prompt에 있다.
7. `rag.generation`에서 OpenAI chat model을 호출하고 답변 및 prompt/completion token usage를 수집한다.
8. `rag.citation_validation`에서 답변이 비어 있지 않고, 적어도 한 개의 `[Sn]`을 포함하며, 모든 참조 번호가 실제 source 범위 안인지 검사한다.
9. `AnswerResponse`는 답변과 source metadata/excerpt, 실제 적용 설정, provider/model, generation 호출 여부와 token usage를 반환한다.

근거: `RagService`, `ContextBuilder`, `RagPromptBuilder`, `OpenAiGenerationGateway`, `CitationValidator`, `AnswerResponse`.

## 5. Document ingestion flow

중요하게도 현재 ingestion은 단일 자동 pipeline endpoint가 아니다. 다음 세 단계가 사용자의 명시적 POST 요청으로 분리되어 동기 실행된다.

### 5.1 Upload 및 parsing

`POST /api/v1/documents` → `DocumentService.create`:

- multipart `file`과 선택적 `title`을 받는다.
- empty, 1MB 초과, filename 없음/255자 초과, `.txt`/`.md` 이외 확장자를 거부한다.
- 경로가 포함된 filename은 slash 기준 basename만 남긴다.
- byte array를 malformed/unmappable character를 허용하지 않는 UTF-8 decoder로 읽고, 선두 BOM은 text에서 제거한다.
- raw byte의 SHA-256을 계산하고 DB 사전 조회와 unique constraint 양쪽으로 중복을 막는다.
- Markdown 전용 AST parsing은 없다. TXT와 Markdown 모두 UTF-8 raw text로 저장된다.
- 최초 상태는 chunking `PENDING`, embedding `NOT_STARTED`다.

근거: `DocumentService.validate/readBytes/decodeUtf8/sourceTypeOf/sha256`, `DocumentRepository.insert`, V1~V3 migration, `DocumentServiceTests`.

### 5.2 Chunking

`POST /api/v1/documents/{id}/process` → `DocumentProcessingService.process`:

1. `DocumentProcessingTransactions.claim`이 atomic `UPDATE ... WHERE status IN ('PENDING','FAILED')`로 claim하고 `PROCESSING`으로 전환한다. FAILED 재시도일 때만 `retry_count`를 증가시킨다.
2. `SpringAiDocumentChunker.split`이 Spring AI `TokenTextSplitter`로 나누고 `JTokkitTokenCountEstimator`로 각 Chunk token 수를 계산한다.
3. 기본 chunk size는 600이며 max 500 chunks/document다. builder에는 chunk size만 전달하므로 custom overlap 정책은 구현하지 않았다.
4. empty/blank/non-positive token 또는 limit 초과를 검증한다.
5. 성공 transaction에서 기존 Chunk를 삭제하고 새 Chunk를 index 0부터 batch insert한 뒤 document를 `READY`, embedding을 `PENDING`으로 만든다.
6. 실패 transaction에서는 Chunk를 모두 삭제하고 document를 `FAILED`로 기록한다.

근거: `DocumentProcessingService`, `DocumentProcessingTransactions`, `DocumentRepository.claimForProcessing/markReady/markFailed`, `DocumentChunkRepository.replace`, `ChunkingConfiguration`, `application.yml`.

### 5.3 Embedding

`POST /api/v1/documents/{id}/embeddings` → `EmbeddingService.process`:

1. 문서 존재와 provider 활성화를 확인한 뒤, document가 chunking `READY`이고 embedding `PENDING` 또는 `FAILED`일 때만 atomic claim한다.
2. claim transaction에서 기존 vector/provider/model을 먼저 clear한다.
3. Chunk를 `chunk_index` 순서로 읽고 기본 32개씩 batch한다.
4. `OpenAiEmbeddingGateway`가 Spring AI `EmbeddingModel.embed`을 호출한다. 기본 모델은 `text-embedding-3-small`, 차원은 1536이다.
5. 각 batch의 응답 수, 각 vector의 1536차원, finite value, all-zero가 아님을 검증한다.
6. 모든 batch가 성공한 뒤에만 `EmbeddingTransactions.complete`가 모든 vector를 batch update하고 document embedding 상태를 `READY`로 만든다. vector는 문자열 literal로 변환한 후 `CAST(... AS vector)`로 저장한다.
7. provider/validation/persistence 실패 시 별도 fail transaction에서 모든 vector metadata를 clear하고 상태를 `FAILED`로 만든다. FAILED에서 다시 claim하면 embedding retry count가 증가한다.

근거: `EmbeddingService`, `EmbeddingTransactions`, `DocumentEmbeddingRepository`, `EmbeddingVectorValidator`, `PgVectorFormatter`, `EmbeddingApiIntegrationTests.providerFailureLeavesNoPartialVectorsAndFailedDocumentCanRetry`.

## 6. Retrieval flow

### Query embedding

- document embedding에 사용한 동일 `EmbeddingGateway`를 query embedding에도 사용한다.
- 한 query를 singleton list로 provider에 넘긴다.
- document vector와 동일한 validator를 거치므로 count 1, configured dimension 1536, finite, non-zero를 강제한다.
- disabled provider는 503, provider 실패/잘못된 응답은 502로 변환한다.

근거: `RetrievalService.searchTraced`, `EmbeddingGateway`, `EmbeddingVectorValidator`, `RetrievalServiceTests`.

### Vector search

`RetrievalRepository.searchSimilar`의 후보 조건은 다음과 같다.

- document chunking 상태 `READY`
- document embedding 상태 `READY`
- Chunk embedding not null
- 저장된 `embedding_provider`와 현재 gateway provider 일치
- 저장된 `embedding_model`과 현재 gateway model 일치
- `vector_dims(embedding)`과 현재 dimensions 일치

각 후보에 `dc.embedding <=> queryVector`로 cosine distance를 계산하고, `distance <= 1 - minimumSimilarity`로 threshold를 적용한다. 외부 similarity는 `1 - distance`다. 정렬은 distance ASC 후 document ID, chunk index, chunk ID 순으로 tie-break하고 `LIMIT topK`를 적용한다. 근거: `RetrievalRepository.searchSimilar`, `RetrievalApiIntegrationTests.returnsExactCosineRankingThresholdAndMetadata`, `.usesDeterministicTieBreakAndReturnsEmptyResultsNormally`.

### Ranking/filtering의 정확한 범위

- ranking은 cosine similarity 하나뿐이다.
- metadata filter는 상태와 embedding configuration뿐이며 사용자 지정 document/tag/date filter는 없다.
- cross-encoder reranker, LLM reranker, BM25/hybrid search, query expansion은 없다.
- 같은 문서의 여러 Chunk를 deduplicate하지 않는다.
- HNSW/IVFFlat index가 없으므로 전체 적격 후보에 대한 exact scan이다. V1에는 일반 B-tree index만 있고 vector index DDL은 없다.

## 7. RAG 관련 핵심 구현

### Evidence-first control flow

`RagService`는 retrieval 결과가 없으면 Generation을 아예 호출하지 않는다. 이 분기는 비용 절감뿐 아니라 근거 없는 답변 생성 경로를 제거한다. 응답의 `generationCalled=false`, 빈 source, null token usage로 이 사실을 외부에서 검증할 수 있다.

### Source identity와 Context

`ContextBuilder`는 검색 순서를 보존해 `[S1]`, `[S2]`를 부여한다. 모델에는 Chunk 전문을 전달하고, API 응답에는 code point 기준 기본 최대 500자의 excerpt만 제공한다. 문서 제목의 CR/LF는 Context metadata에서 공백으로 바꾸지만 Chunk 본문은 그대로 둔다. 근거: `ContextBuilder.build/normalizeMetadata/excerpt`, `AnswerProperties`.

### Prompt 경계

system prompt는 다음 정책을 코드 상수로 고정한다: 제공 Context만 사용, 근거 부족 고정문, 각 주장에 source citation, Context 내부 명령 불신, 존재하지 않는 source 금지, 질문 언어로 간결히 답변. question과 Context는 user prompt 안에서 `QUESTION`/`CONTEXT` section으로 분리된다. 근거: `RagPromptBuilder.SYSTEM_PROMPT/build`.

### 출력 validation

`CitationValidator`는 blank 답변, citation 없음, 숫자가 아닌 source ID, source count 밖의 ID를 거부한다. 유효한 citation이 하나라도 있고 모든 나타난 ID가 범위 안이면 통과한다. 따라서 다음은 구현되어 있지 않다.

- 모든 문장/주장마다 citation이 실제로 붙었는지 semantic 검사
- citation이 가리킨 Chunk가 해당 주장을 entail하는지 검사
- citation source의 완전성 또는 answer factuality 검사
- 고정 거절문을 모델 출력에서 별도로 허용하는 로직. 모델은 retrieval hit가 있을 때 최소 citation을 내야 한다.

근거: `CitationValidator.validate`, `ContextAndCitationTests`.

### Generation adapter

OpenAI adapter는 system/user message를 `Prompt`로 전달하고 첫 output text와 provider usage의 prompt/completion token을 자체 `GenerationResult`로 변환한다. temperature 기본 0.0, max output 600이다. automatic retry는 1회 시도로 사실상 비활성이다. 근거: `OpenAiGenerationGateway.generate`, `GenerationConfiguration`, `GenerationProperties`.

## 8. OpenTelemetry 적용 범위

### 적용된 범위

- Spring의 HTTP server observation이 request trace의 상위 span을 제공한다. 테스트는 custom `rag.answer`가 SERVER span의 child인지 검증한다. 근거: `TracingHttpTests.answerHasCorrectHierarchyUsageAndSafeMetadata`.
- search/answer application flow에 `RagTracing.trace`로 7종 custom span을 만든다.
- RAG 설정·결과 개수·provider/model/dimension·token usage·결과 유형·안전한 오류 코드를 tag로 남긴다.
- Micrometer tracing bridge OTel과 OTLP HTTP exporter로 Collector에 전송한다. Collector는 batch 후 Tempo OTLP/gRPC로 보낸다.
- Grafana에는 Tempo datasource가 default로 provision된다.
- service resource name은 `rag-observability`로 설정되고 테스트된다.

근거: `pom.xml`, `application.yml`, `RagTracing`, `infra/otel-collector/config.yaml`, `infra/grafana/provisioning/datasources/tempo.yaml`, `TracingHttpTests`.

### 적용되지 않은 범위

- document upload/chunking/document embedding에는 `rag.*` custom span이 없다.
- document Embedding provider 호출도 현재 custom span 대상이 아니다. `rag.query_embedding`은 retrieval query embedding만 감싼다.
- custom metric/Prometheus, log export/Loki, trace-log correlation 코드는 없다.
- Collector pipeline은 traces만 정의한다. metrics/logs pipeline, persistent queue, retry/storage extension은 설정되어 있지 않다.
- OpenAI outbound HTTP나 JDBC에 대해 이 repository가 명시적으로 만드는 client/database span은 없다. 자동 instrumentation 여부에 기대는 설명은 코드만으로 확정하지 않는다.
- Grafana dashboard JSON은 없고 Tempo datasource만 provision한다.

## 9. Trace / Span 구조

### 정상 답변

```text
HTTP SERVER span (Spring observation)
└─ rag.answer
   ├─ rag.retrieval
   │  ├─ rag.query_embedding
   │  └─ rag.vector_search
   ├─ rag.context_build
   ├─ rag.generation
   └─ rag.citation_validation
```

`TracingHttpTests.answerHasCorrectHierarchyUsageAndSafeMetadata`가 custom span 7개, parent-child 관계, generation input token tag, service.name을 검증한다.

### 검색만

```text
HTTP SERVER span
└─ rag.retrieval
   ├─ rag.query_embedding
   └─ rag.vector_search
```

### 근거 부족 답변

```text
HTTP SERVER span
└─ rag.answer                rag.outcome=no_evidence
   └─ rag.retrieval          rag.generation.called=false
      ├─ rag.query_embedding
      └─ rag.vector_search   rag.outcome=empty
```

Context, Generation, citation validation span은 생성되지 않는다. 근거: `RagService.answerTraced`, `TracingHttpTests.emptyResultsSkipGenerationAndRemainSuccessful`.

### 주요 span attribute

| Span | attribute |
|---|---|
| `rag.answer` | `rag.generation.called`, `rag.outcome`, 오류 시 `rag.error.code` |
| `rag.retrieval` | `rag.top_k`, `rag.minimum_similarity`, `rag.result_count`, `rag.outcome` |
| `rag.query_embedding` | `rag.embedding.provider`, `.model`, `.dimensions` |
| `rag.vector_search` | `rag.result_count`, empty일 때 `rag.outcome=empty` |
| `rag.context_build` | `rag.source_count` |
| `rag.generation` | `rag.generation.provider`, `.model`, optional input/output tokens |
| 모든 custom span | 시작 시 `rag.outcome=success`; exception이면 `error`, 허용 목록의 `rag.error.code`, error status/event |

질문, Context, Prompt, Chunk 전문, vector, provider 원문 오류는 tag로 남기지 않는다. `RagTracing`은 원래 exception 대신 stack/cause가 없는 `SafeTraceError(code)`를 span error로 기록하고 원래 exception은 caller에 다시 던진다. `RagTracingTests.exceptionIsRethrownAndScopeAndSpanAreClosedWithSanitizedError`와 `TracingHttpTests.failuresAreSanitizedAndLaterStagesNotExecuted`가 이를 검증한다.

## 10. X-Trace-Id 생성 및 전달 구조

1. 애플리케이션 코드가 UUID나 임의 trace ID를 직접 생성하지 않는다.
2. 활성 tracing 환경에서는 Spring/Micrometer가 HTTP server request의 current span을 제공한다.
3. `TraceHeaderFilter`는 `/api/v1/search`와 `/api/v1/answers`에만 동작하고, `RagTracing.currentTraceId()`로 current span context의 trace ID를 읽는다.
4. 값이 있을 때 response header `X-Trace-Id`를 chain 실행 전에 설정한다.
5. 프론트 `request()`는 응답 header를 읽어 success envelope 또는 `ApiError.traceId`에 보존한다. UI의 `Trace` component가 복사와 Tempo 조회 방법을 제공한다.
6. tracing이 disabled이거나 Tracer/current span이 없으면 header를 만들지 않는다.

근거: `TraceHeaderFilter`, `RagTracing.currentTraceId`, `frontend/src/api.ts`, `frontend/src/App.tsx`, `RagTracingTests.disabledOrAbsentTracerExecutesWorkWithoutHeader`.

정확한 제한도 있다.

- 이 custom header는 documents endpoint나 actuator health에는 추가되지 않는다.
- request의 `X-Trace-Id`를 읽거나 신뢰하는 코드는 없다. 표준 propagation header 처리도 애플리케이션 코드에 직접 구현되어 있지 않다.
- sampling probability가 1보다 작으면 유효한 response trace ID가 있어도 Tempo에 export되지 않을 수 있다.
- `TracingHttpTests`는 header가 32자리 lowercase hex이며 exported span의 trace ID와 같음을 검증한다.

## 11. DB schema 및 pgvector 사용 방식

### `document`

| 컬럼/제약 | 용도 |
|---|---|
| `id BIGSERIAL PK` | document identity |
| title/original filename/source type/raw content | 원문과 표시 metadata |
| `content_sha256 CHAR(64) UNIQUE` | raw upload byte 기준 중복 방지 |
| `status` | Chunking 상태: PENDING/PROCESSING/READY/FAILED |
| chunking error/retry fields | 안전한 실패 코드·메시지와 FAILED 재처리 횟수 |
| `embedding_status` | NOT_STARTED/PENDING/PROCESSING/READY/FAILED |
| embedding error/retry fields | embedding 독립 실패 상태 |
| timestamps | 생성/상태 변경 시각 |

근거: V1~V3 migration, `DocumentRepository`.

### `document_chunk`

| 컬럼/제약 | 용도 |
|---|---|
| `document_id` FK `ON DELETE CASCADE` | 문서 삭제 시 Chunk/vector hard delete |
| `chunk_index` + unique(document, index) | 문서 내 결정적 순서 |
| `content`, nullable `token_count` | Chunk 본문과 추정 token 수 |
| provider/model | vector 생성 configuration |
| `embedding VECTOR(1536)` | pgvector embedding |
| validation constraints | non-negative index/token count |

V1은 `CREATE EXTENSION IF NOT EXISTS vector`를 실행한다. vector 저장은 JDBC driver 전용 타입 매핑이 아니라 `PgVectorFormatter`의 안전한 literal + SQL `CAST(:embedding AS vector)` 방식이다. 검색은 `<=>` cosine distance와 `vector_dims()`를 직접 사용한다. 근거: V1, `DocumentEmbeddingRepository.saveAll/findStatus`, `RetrievalRepository.searchSimilar`.

일반 index는 document status와 document_chunk.document_id뿐이다. vector index는 없다. 따라서 현재 구조는 작은 corpus의 정확하고 재현 가능한 baseline에는 적합하지만 corpus 증가 시 scan 비용이 증가한다. 이 trade-off는 ADR-0002에 명시되어 있다.

스키마에 chunk size/splitter version/ingestion version은 저장되지 않는다. 실제 baseline manifest도 `600 tokens (operator assumption; not persisted in schema)`라고 기록한다. 이는 재현성의 중요한 한계다. 근거: V1~V3, `EvaluationReport`, `RunManifest`.

## 12. Golden Query / evaluation 구조

### 데이터셋

- legacy `evaluation/retrieval-golden-set.json`: 15개(DIRECT 6, PARAPHRASED 6, UNRELATED 3).
- validation `evaluation/retrieval-validation-set-v1.json`: formatVersion 2, dataset ID, 세 source file의 SHA-256, 18개(DIRECT 6, PARAPHRASED 6, HARD_NEGATIVE 3, UNRELATED 3), relevant document/chunk hint, judgment reason.
- `GoldenDataset.validate`는 query count/category balance, ID/question uniqueness, negative query의 null expected document, source file set/hash, legacy 질문과 신규 질문의 중복 금지를 검사한다.

### live retrieval baseline

`scripts/run-evaluation.ps1 -Live`는 먼저 plain Java offline validation을 하고, evaluation profile로 Spring을 web/tracing/generation 없이 실행한다. `EvaluationRunner`는:

1. dataset과 local source hash를 검증한다.
2. output 파일을 paid request 전에 선점해 기존 결과 overwrite를 막는다.
3. `EvaluationCorpus`로 정확히 세 frozen document만 있고, 모두 READY이며 현재 provider/model/1536 vector를 갖고, 추가 eligible document가 없는지 확인한다.
4. 각 query를 Top-K 5, threshold -1.0으로 한 번 검색한다. Generation 호출은 없다.
5. 정답 document의 첫 rank, Hit@5, reciprocal rank, query embedding+DB search latency를 기록한다.
6. `queries.csv`, `hits.csv`, `summary.md`, v2 dataset이면 `run.json` manifest와 artifact hashes를 남긴다.
7. 실패 시 이후 query를 중단하고 partial output/INCOMPLETE와 allowlist error code만 남긴다.

근거: `DatasetValidationMain`, `EvaluationCorpus`, `EvaluationRunner`, `EvaluationReport`, `RunManifest`, `application-evaluation.yml`.

### offline threshold/Top-K 비교

- `ThresholdBaseline.load`는 COMPLETE marker, 정확한 CSV header/row/rank, sorted similarity, result count, baseline score, manifest/hash 일관성을 재검증한다.
- `ThresholdCalculator`는 저장된 Top-5에 threshold를 재적용한다. 더 낮은 순위를 새로 검색하지 않으므로 “후처리 비교”다.
- `TopKCalculator`는 threshold 0.3을 고정하고 K=1/2/3/5로 저장 hit를 자른다. API/DB 호출은 없다.
- 지표는 related query의 Hit/MRR/empty rate, HARD_NEGATIVE와 UNRELATED의 return rate, mean chunk/document count를 분리한다. 문서 중복은 ranking에서 제거하지 않는다.

근거: `ThresholdBaseline`, `ThresholdCalculator`, `TopKCalculator`, 두 ComparisonMain/Report.

### Chunk Size 비교

test-scope `ChunkSizeExperimentMain`은 300/600/900 조건을 각각 새 pgvector Testcontainer에서 실행한다. 같은 frozen source를 실제 splitter로 나누고, 18개 query vector는 최초 한 번만 생성해 `QueryReplayGateway`에서 defensive copy로 재사용하며, 각 조건에서는 document embedding만 다시 만든다. 실제 `DocumentService`→Chunking→Embedding→Retrieval SQL을 거치며 총 54 search를 실행한다. 설정은 Top-K 5, threshold 0.3이다. 근거: `ChunkSizePlan`, `ChunkSizeExperimentMain`, `QueryReplayGateway`, `ChunkSizeIntegrationTests.realServicesUseThreeIsolatedDatabasesAnd54ReplaySearches`.

### 저장된 실측 결과

로컬 `evaluation/results/`에는 validation baseline COMPLETE와 비교 결과가 있다.

- validation baseline: 18/18, Hit@5 1.0000, MRR@5 0.9167. 근거: `evaluation/results/2026-09-05T11-49-05.080986300Z-37efaa7c-f685-45ec-8dd4-ee87aac55c07/summary.md`.
- threshold 0.3: related Hit@5 1.0000/MRR 0.9167, all-negative return 0.5; HARD_NEGATIVE는 모두 남고 UNRELATED는 모두 제거됐다. threshold 0.5에서는 Hit@5 0.3333이며 hard-negative return 0.3333이었다. 근거: `evaluation/results/threshold-comparison-2026-09-05T11-56-28.069288700Z-d081fd1b-c724-47d5-9eb4-cea29fd9f1eb/summary.md`.
- threshold 0.3의 Top-K 비교: K=1 Hit 0.8333/MRR 0.8333, K≥2 Hit 1.0000/MRR 0.9167. negative return은 K와 무관하게 0.5다. 근거: `evaluation/results/topk-comparison-2026-09-05T12-10-16.339038700Z-b616350e-8086-4ee7-9c92-e5c29c01b928/summary.md`.
- Chunk Size: 300/600/900 모두 Hit@5 1.0000; MRR은 각각 0.9583/0.9167/0.9444, negative return은 0.6667/0.5/0.5. 근거: `evaluation/results/chunk-size-comparison-2026-09-05T16-34-49.776580300Z-4f464dfe-38e8-4e8e-a937-3eace986d88d/summary.md`.

이 숫자는 작은 동일 dataset에서의 실측이며 운영 일반화 근거가 아니다. 또한 `evaluation/results/`는 Git ignored이므로 clone만으로 제공되지 않는다. 프론트의 `experiments.json`은 allowlist export 사본이지만 원본 보고서 전체를 대체하지 않는다.

## 13. 테스트 코드와 검증 방식

### Backend unit/service test

- upload parsing/validation, SHA 중복 race: `DocumentServiceTests`.
- splitter ordering/token count: `SpringAiDocumentChunkerTests`.
- Chunk failure mapping/limit: `DocumentProcessingServiceTests`.
- Embedding batch 32/32/6, count/dimension/finite/non-zero validation, disabled provider: `EmbeddingServiceTests`.
- retrieval normalization/default/override/error sanitization: `RetrievalServiceTests`.
- Context/source order, excerpt, prompt separation, citation validation: `ContextAndCitationTests`.
- RAG no-evidence/provider/invalid response: `RagServiceTests`.
- provider timeout/no retry와 adapter mapping: `OpenAiEmbeddingGatewayTimeoutTests`, `OpenAiGenerationGatewayTimeoutTests`, `OpenAiGenerationGatewayTests`.

### DB/HTTP integration test

Testcontainers의 실제 pgvector image와 Flyway를 사용해 document CRUD, 상태 claim 충돌, chunk atomic failure/retry, embedding partial vector 제거/retry, V2→V3 migration, exact cosine SQL/filter/tie-break, end-to-end upload→chunk→embedding→search→answer를 검증한다. 근거: `DocumentApiIntegrationTests`, `DocumentProcessingFailureIntegrationTests`, `EmbeddingApiIntegrationTests`, `EmbeddingStateMigrationTests`, `RetrievalApiIntegrationTests`, `RagApiIntegrationTests`.

### Observability test

`TracingHttpTests`는 in-memory span exporter로 실제 HTTP server span과 custom hierarchy/header/tag/error sanitization을 검증하고, exporter를 접근 불가능한 주소로 구성해 export 실패가 API success를 깨지 않는지도 시험한다. `-Dobservability.live=true`에서만 Collector→Tempo round trip을 추가 검증한다.

### Evaluation test

dataset corruption, hash/model/corpus mismatch, extra eligible document, paid call 이전 output/preflight, failure stop, manifest/CSV tampering, threshold/Top-K denominator와 monotonicity, 세 isolated DB의 Chunk Size 실험, query vector replay와 safe incomplete report를 검증한다. 근거: `EvaluationUnitTests`, `EvaluationIntegrationTests`, `ValidationDatasetTests`, `ThresholdComparisonTests`, `TopKTests`, `ChunkSizeUnitTests`, `ChunkSizeIntegrationTests`.

### Frontend test

- Vitest: request normalization, trace header, 204, network uncertainty/no retry, invalid JSON, safe experiment export.
- Playwright demo: keyboard, 1440/768/375 responsive view, demo API call 0회, live errors, duplicate request prevention, citation/trace, text safety.
- opt-in live browser test: Browser→Vite proxy→Spring→isolated pgvector→Fake AI 전체 경로.

근거: `frontend/src/api.test.ts`, `frontend/e2e/demo.spec.ts`, `frontend/e2e/live.spec.ts`, `FrontendHttpIntegrationTests`.

### 현재 확인 가능한 실행 기록

감사 시점에 `mvnw clean test`를 다시 실행했고 총 128개, 124 pass, 4 skip, failure/error 0으로 성공했다. Docker가 동작해 pgvector Testcontainers 통합 테스트도 실제 실행됐다. 기본 suite의 skip은 OpenAI live 2개, Tempo live 1개, opt-in frontend HTTP 1개다. 별도로 Vitest 16개가 모두 통과했고 TypeScript 검사와 Vite production build도 성공했다. Tempo live test는 `-Dobservability.live=true`로 별도 실행해 7/7 통과했고 정상 답변과 no-evidence Trace를 실제 Collector–Tempo 경로에서 조회했다. Testcontainers test는 코드상 `disabledWithoutDocker=true`이므로 다른 환경에서 Docker가 없으면 일부 검증이 skip될 수 있다. 이번 갱신에서는 실제 OpenAI와 browser opt-in test는 재실행하지 않았다. 명령·환경·dirty worktree 조건은 `docs/evidence/test-verification-2026-09-06.md`, Trace ID와 Span 수는 `docs/evidence/observability-verification-2026-09-06.md`에 기록했다.

## 14. 예외 처리 / timeout / observability 관련 구현

### API 오류 계약

`ApiExceptionHandler`가 domain exception을 RFC 9457 계열 `ProblemDetail`로 변환하고 `code`, HTTP status, request URI를 제공한다. body validation은 fieldErrors를 포함한다. malformed/unknown JSON, type mismatch, missing part/parameter, max upload도 명시적 안전 메시지로 매핑한다. 단, 모든 예상 밖 exception을 받는 catch-all handler는 없다.

### 상태와 동시성

- chunk/embedding claim은 조건부 single `UPDATE`라 동시에 두 요청이 상태를 획득하지 못한다.
- 이미 PROCESSING/READY인 단계 재호출은 409로 거부된다.
- `REQUIRES_NEW` claim/complete/fail transaction으로 외부 작업 실패 후에도 상태 transition이 commit된다.
- complete transaction은 Chunk replace+document READY, 또는 all vector save+embedding READY를 하나로 묶는다.
- 문서 delete는 FK cascade를 사용하는 hard delete다.

근거: `DocumentProcessingTransactions`, `EmbeddingTransactions`, 각 Repository와 failure integration tests.

### Timeout/retry

| 구간 | 기본값/정책 | 근거 |
|---|---|---|
| Hikari connection acquisition | 3초 | `application.yml` |
| Embedding connect/read | 3초/30초 | `EmbeddingProperties`, `EmbeddingConfiguration` |
| Generation connect/read | 3초/60초 | `GenerationProperties`, `GenerationConfiguration` |
| OpenAI automatic retry | maxAttempts(1), no backoff | 두 Configuration |
| Frontend request abort | 600초 | `frontend/src/api.ts` |
| Frontend POST retry | 없음; network error는 uncertain state | `frontend/src/api.ts`, `api.test.ts` |
| graceful shutdown | enabled | `application.yml` |

Provider timeout은 provider exception으로 감싸지고 API에서는 원문 오류를 노출하지 않는 502로 변환된다. timeout test는 지연 HTTP server에 요청이 1회만 들어오는지 검증한다.

### Observability 안전성

- 오류 log는 document ID와 exception class 또는 stage만 남기며 query/provider 원문 메시지를 직접 기록하지 않는다.
- trace error에는 allowlist code 또는 `INTERNAL_ERROR`만 남긴다.
- evaluation CLI도 exception message/stack을 출력하지 않고 safe code만 기록한다.
- span scope는 try-with-resources로 닫고 finally에서 end한다.

근거: `DocumentProcessingService`, `EmbeddingService`, `RetrievalService`, `RagService`, `RagTracing`, `EvaluationRunner`.

구현되지 않은 resilience는 circuit breaker, bulkhead, exponential backoff retry, request cancellation의 provider 전파 보장, job queue/dead-letter queue, automatic scheduled retry다.

## 15. 기술적으로 설명할 가치가 높은 문제 해결 사례 5개

### 사례 1: 실패 상태를 잃지 않는 외부 API 작업 transaction 설계

문제는 Embedding 호출을 한 transaction에 묶으면 외부 호출 중 connection을 오래 점유하고, exception rollback으로 실패 상태까지 사라질 수 있다는 점이다. 구현은 claim/외부 호출/complete-or-fail을 분리하고, 상태 변경 transaction을 `REQUIRES_NEW`로 실행한다. 모든 batch가 검증된 뒤에만 vector를 저장하며 실패 시 기존 vector까지 clear한다. 면접에서는 atomic claim, 재시도 횟수, partial write 방지, 외부 호출을 transaction 밖에 둔 이유를 함께 설명할 수 있다. 근거: `EmbeddingService`, `EmbeddingTransactions`, `EmbeddingApiIntegrationTests.providerFailureLeavesNoPartialVectorsAndFailedDocumentCanRetry`.

### 사례 2: 추상화보다 설명 가능성을 택한 pgvector 직접 SQL

`VectorStore`를 쓰지 않고 `<=>`, `1 - distance`, READY/provider/model/dimension filter와 deterministic tie-break를 SQL에 노출했다. 이로써 실험 결과와 실제 검색 정책이 같은 코드 경로를 쓰고, 모델 교체 후 오래된 vector가 섞이는 문제도 query 조건으로 방지한다. 대가는 vector serialization과 SQL portability를 직접 책임지는 것이다. 근거: ADR-0001, `PgVectorFormatter`, `RetrievalRepository`, `RetrievalApiIntegrationTests`.

### 사례 3: RAG hallucination 경로를 제어하는 no-evidence/citation gate

threshold를 통과한 근거가 없으면 LLM을 호출하지 않고 고정 응답을 반환한다. 근거가 있을 때도 model이 인용을 생략하거나 가짜 source 번호를 만들면 502로 차단한다. 이는 prompt만 신뢰하지 않고 application-level output contract를 둔 사례다. 다만 semantic faithfulness까지 검증하지는 않는다는 한계를 함께 말해야 한다. 근거: `RagService`, `CitationValidator`, `RagApiIntegrationTests`.

### 사례 4: 민감 정보를 남기지 않는 단계별 trace

RAG의 latency/failure stage는 보고 싶지만 질문, Context, API key, provider error는 trace에 남기면 안 된다. 구현은 값 대신 topK, threshold, model metadata, count, token usage, allowlist error code만 tag하고, cause/stack 없는 `SafeTraceError`를 기록한다. 테스트는 `SECRET_` marker가 span attribute/event/status에 없음을 검증한다. 근거: `RagTracing`, `TracingHttpTests.clean/failuresAreSanitizedAndLaterStagesNotExecuted`.

### 사례 5: 비용과 데이터 누수를 통제하는 재현 가능한 평가

평가 전에 dataset/source/corpus/model/hash/추가 문서를 검증하고 output을 먼저 예약한다. validation query는 정확히 18회만 embedding하며 Generation은 0회다. threshold/Top-K는 저장 결과를 offline 재채점하고, Chunk Size 비교는 query vector를 한 번 생성해 세 isolated DB에서 재사용한다. 실패하면 즉시 중단하고 COMPLETE 지표를 발행하지 않는다. 이는 평가 비용, corpus contamination, cherry-picking과 partial success 오인을 줄이는 설계다. 근거: `EvaluationRunner`, `EvaluationCorpus`, `EvaluationReport`, `RunManifest`, `ChunkSizeExperimentMain`, evaluation tests.

## 16. 면접에서 질문받을 가능성이 높은 기술적 의사결정

### 왜 Spring AI VectorStore가 아닌 직접 SQL인가?

답변 근거: 검색 정책과 SQL 실행을 포트폴리오에서 설명하고, exact cosine/threshold/filter/tie-break를 직접 검증하기 위해서다. 비용은 DB portability와 mapping 코드다. `docs/adr/0001-use-direct-pgvector-sql.md`.

### 왜 HNSW/IVFFlat가 아니라 exact search인가?

현재 corpus가 작고 Chunk Size/Top-K 품질 baseline에서 ANN recall 변수를 제거하려는 선택이다. 대규모 성능을 증명한 선택은 아니다. `docs/adr/0002-start-with-exact-vector-search.md`, V1 migration.

### 왜 embedding과 generation gateway를 분리했는가?

입출력, 비용, timeout, 실패 영향, model lifecycle이 다르다. embedding model 변경은 저장 vector 재생성이 필요하지만 chat model 변경은 그렇지 않다. 독립 fake와 error mapping도 가능해진다. `docs/adr/0003-separate-embedding-and-generation-gateways.md`.

### 왜 chunking/embedding을 자동 비동기 pipeline이 아니라 수동 endpoint로 나눴는가?

코드상 각 단계의 비용과 상태를 사용자가 명시적으로 제어하고 failure/retry를 관찰하기 쉬운 구조다. 다만 queue/worker나 production throughput을 구현한 것은 아니다. `DocumentController`, 두 상태 머신.

### 왜 vector dimension을 1536으로 고정했는가?

DB `VECTOR(1536)`과 provider response mismatch를 startup/response/save 전에 fail-fast하기 위해서다. 대신 다른 dimension model로의 전환은 migration과 전체 re-embedding이 필요하다. V1, `EmbeddingProperties.DATABASE_DIMENSIONS`, `EmbeddingVectorValidator`.

### 왜 provider/model을 Chunk마다 저장하고 search에서 다시 filter하는가?

과거 vector와 현재 query vector를 다른 embedding space에서 비교하는 오류를 막기 위해서다. `DocumentEmbeddingRepository.saveAll`, `RetrievalRepository.searchSimilar`.

### threshold 기본값이 search 0.0, answer 0.5로 다른 이유는?

검색 API는 결과 관찰용이고 답변 API는 no-evidence gate 역할을 하도록 별도 property를 쓴다. 그러나 현재 validation 결과는 0.5가 related recall을 크게 낮추고, 0.3도 hard negative를 제거하지 못함을 보여준다. 따라서 기본 0.5는 최적값이 입증된 결론이라고 말하면 안 된다. `RetrievalProperties`, `AnswerProperties`, validation threshold summary.

### 왜 retry를 끄고 timeout만 두었는가?

자동 재시도로 비용·중복·latency가 숨는 것을 피하고 실패를 명시적으로 상태화한 선택이다. 현재 backoff/circuit breaker가 없으므로 운영 resilience 완성으로 표현해서는 안 된다. 두 provider Configuration과 timeout tests.

### `X-Trace-Id`를 왜 응답에 주는가?

사용자가 해당 요청의 server/custom spans를 Tempo에서 바로 찾게 하기 위해서다. custom ID가 아니라 활성 server trace의 ID를 그대로 반환해 correlation을 유지한다. `TraceHeaderFilter`, `TracingHttpTests`.

### 평가에서 왜 문서 수준 Hit/MRR를 쓰는가?

frozen corpus의 retrieval baseline과 ranking 변화를 단순·재현 가능하게 보기 위해서다. 하지만 Chunk가 실제 답을 포함하는지와 생성 답변 품질은 별도 문제다. `EvaluationMetrics`, 각 report의 Limits.

## 17. 아직 포트폴리오에서 증명하기 부족한 부분

### 검색/RAG 품질

1. corpus 3개·validation 18개는 설계 시연에는 충분하지만 일반화, 도메인 다양성, 통계적 유의성을 증명하지 못한다.
2. 정답 label은 document 수준이며 `relevantChunkHint`를 실제 Chunk-level scoring에 사용하지 않는다. 근거 충분성, context precision/recall, answer correctness/faithfulness 자동 평가는 없다.
3. citation validator는 형식/범위만 검사한다. source가 주장을 뒷받침하는지는 검사하지 않는다.
4. threshold 0.3에서도 HARD_NEGATIVE 3개가 모두 반환됐고, 운영 answer default 0.5에서는 validation related Hit@5가 0.3333이었다. 현재 default가 최적이라고 증명되지 않았다.
5. prompt injection은 system prompt 정책과 수동 기록은 있으나, 공격 corpus와 자동 assertion으로 방어 효과를 입증하는 전용 test는 없다.

### Retrieval 확장성

1. vector index와 대규모 corpus benchmark/load test가 없다. exact scan의 p95는 18개 exploratory query이며 서버 부하 성능이 아니다.
2. hybrid/BM25, reranking, metadata filtering, diversity/document dedup, query rewrite가 없다.
3. embedding dimension/model migration 및 zero-downtime re-index 전략이 없다.
4. chunk size/splitter/version provenance가 DB에 없어서 stored vector의 생성 조건을 DB만으로 재구성할 수 없다.

### Ingestion/운영성

1. PDF, HTML, DOCX, URL, OCR, structured Markdown parsing과 metadata extraction이 없다.
2. 수동 동기 endpoint라 queue, background worker, progress, cancellation, scheduled retry, DLQ가 없다.
3. upload hash가 raw byte 기준이라 BOM/line-ending/Unicode normalization만 다른 같은 의미의 문서를 별개로 볼 수 있다.
4. 인증/인가, tenant isolation, rate limit, quota/cost budget, audit log가 없다. 로컬 외 공개 운영을 증명하지 않는다.
5. CI workflow, application container image, deployment manifest, backup/restore, schema rollback runbook이 repository에 없다.

### Observability

1. custom span이 retrieval/answer에만 있고 upload/chunking/document embedding을 추적하지 않는다.
2. metrics/log pipeline, dashboard, alert rule, SLI/SLO가 없다.
3. Collector persistent buffering이 없어 장애 중 trace 유실 복구를 보장하지 않는다.
4. `X-Trace-Id`는 두 AI endpoint에만 있으며 document processing 실패와 직접 연결할 수 없다.
5. 실제 OpenAI live test와 실제 Tempo test는 기본 suite에서 opt-in/skip이다. 저장된 trace ID는 문서에 있으나 Tempo retention은 24시간이므로 장기 증거가 아니다.

### 프론트엔드/포트폴리오 증거

1. demo mode의 답변·점수는 static illustration이며 실제 model 결과가 아니다. UI가 이 사실을 표시하지만 시연에서도 반드시 구분해야 한다. `frontend/src/App.tsx`.
2. `evaluation/results/` 원본은 Git ignored이다. 제출 repository만으로 실측 report provenance를 검증하려면 공개 가능한 결과 bundle 또는 CI artifact 보존 방식이 필요하다.
3. `docs/rag-lab-results.md`는 일부 실측 행이 있으나 completion checkbox와 no-evidence 비교 표가 비어 있어 “완료된 자동 RAG 품질 검증” 근거로는 부족하다.
4. dependency/security 검증은 `npm audit` 수동 기록 외에 자동화가 없다. SAST, secret scan, dependency update 정책 증거도 없다.

## 포트폴리오에서 사실대로 강조할 수 있는 메시지

이 프로젝트를 “production-ready RAG 플랫폼”이라고 표현하기보다는 다음처럼 설명하는 편이 코드와 증거에 정확히 맞는다.

> Spring Boot와 pgvector로 evidence-first RAG의 ingestion, exact retrieval, cited generation을 구현하고, 상태 머신과 transaction 경계로 partial failure를 통제했다. RAG 각 단계를 OpenTelemetry span으로 분리해 실패·latency를 추적했으며, frozen dataset과 hash/manifest 기반 평가로 threshold, Top-K, Chunk Size의 trade-off를 재현했다. 현재 평가는 소규모 document-level baseline이며, answer faithfulness와 대규모 ANN 운영은 후속 과제로 명시했다.
