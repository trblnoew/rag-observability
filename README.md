# RAG Observability

Top-K 1/2/3/5의 무료 오프라인 비교는 [Top-K 실습 안내](docs/topk-validation.md)를 참고하세요. 실험 threshold는 0.3이며 운영 기본값은 변경하지 않습니다.

운영 문서를 Chunk와 Embedding으로 변환해 PostgreSQL/pgvector에 저장하고, 근거가 포함된 RAG 답변을 반환하며, 처리 흐름을 OpenTelemetry Trace로 검증하는 Spring Boot 포트폴리오 프로젝트입니다.

## 고정한 개발 환경

- Java 21 LTS (Eclipse Temurin)
- Spring Boot 3.5.16
- Spring AI 1.1.8
- Maven Wrapper
- PostgreSQL 17 + pgvector 0.8.6
- Flyway
- OpenTelemetry Collector 0.159.0
- Tempo 2.10.8
- Grafana 13.1.0

AI 기능은 API 키 없이도 애플리케이션이 부팅되도록 기본값이 `none`입니다. 자동설정 Starter 대신 Spring AI OpenAI 핵심 라이브러리를 사용하며, 공급자 타입이 도메인으로 새지 않도록 `EmbeddingGateway` Adapter를 수동 구성합니다.
Embedding과 답변 생성은 비용·timeout·실패 영향이 다르므로 `EmbeddingGateway`와 `GenerationGateway`로 분리합니다.

### 버전 선택 이유와 trade-off

| 선택 | 이유 | 감수하는 비용 |
|---|---|---|
| Java 21 LTS | 장기 지원과 현재 Spring 생태계 호환성을 함께 확보 | 더 최신 Java 기능은 baseline에서 사용하지 않음 |
| Spring Boot 3.5.16 | Spring AI 1.1 계열과 검증된 안정 조합이며 기존 Spring 경험을 활용 가능 | Boot 4의 최신 API와 개선 사항을 즉시 사용하지 않음 |
| Spring AI 1.1.8 | Boot 3.5 baseline과 맞고 모델 연동에 필요한 공통 기능을 제공 | Spring AI 2의 breaking change와 신규 기능은 추후 migration 필요 |
| Maven Wrapper 3.9.16 | 개발자와 CI가 동일한 Maven 버전을 사용 | Wrapper 버전 업데이트를 별도로 관리해야 함 |

MVP가 완료되기 전에는 보안 수정이나 치명적 결함이 아닌 major upgrade를 하지 않습니다. Spring Boot 4 / Spring AI 2 전환은 검색 품질과 Trace baseline을 재현한 뒤 별도 ADR로 검토합니다.

## Architecture Decision Records

- [ADR-0001: Spring AI VectorStore 대신 직접 pgvector SQL 사용](docs/adr/0001-use-direct-pgvector-sql.md)
- [ADR-0002: Exact Vector Search부터 시작](docs/adr/0002-start-with-exact-vector-search.md)
- [ADR-0003: EmbeddingGateway와 GenerationGateway 분리](docs/adr/0003-separate-embedding-and-generation-gateways.md)
- [ADR-0004: Spring Boot 3.5.16 / Spring AI 1.1.8 baseline](docs/adr/0004-pin-spring-boot-and-spring-ai-baseline.md)

## 최초 실행

Windows를 재부팅한 뒤 Docker Desktop을 한 번 실행하고 엔진이 준비될 때까지 기다립니다. 이후 PowerShell에서 다음을 실행합니다.

```powershell
.\scripts\check-env.ps1
.\scripts\dev.ps1 up
.\mvnw.cmd clean test
.\scripts\run-app.ps1
```

확인 주소:

- Spring Boot health: <http://localhost:8080/actuator/health>
- Grafana: <http://localhost:3000>
- Collector health: <http://localhost:13133>

Grafana는 로컬 개발용으로 로그인 화면을 비활성화했고 Tempo datasource를 자동 등록합니다.

## 자주 쓰는 명령

```powershell
# 전체 인프라 상태
.\scripts\dev.ps1 status

# 전체 인프라 로그
.\scripts\dev.ps1 logs

# 컨테이너 중지(데이터 볼륨은 유지)
.\scripts\dev.ps1 down

# 의존성 및 테스트 검증
.\mvnw.cmd clean verify
```

DB 데이터까지 삭제하는 `docker compose down -v`는 복구가 어려우므로 문서의 기본 명령에 포함하지 않았습니다.

## Document API

현재는 TXT/Markdown 원문 등록과 조회, 제목 변경, 명시적 Chunking과 Embedding, 삭제를 지원합니다. Chunking 상태와 Embedding 상태는 독립적으로 관리되며 API로 원문이나 처리 상태를 직접 변경할 수는 없습니다.

```powershell
# TXT 문서 등록(제목을 생략하면 파일명에서 생성)
curl.exe -X POST "http://localhost:8080/api/v1/documents" `
  -F "file=@README.md;type=text/markdown" `
  -F "title=DB Timeout Runbook"

# 문서 목록과 상태 필터
curl.exe "http://localhost:8080/api/v1/documents?page=0&size=20&status=PENDING"

# 상세 조회
curl.exe "http://localhost:8080/api/v1/documents/1"

# 원문을 600-token 기준으로 Chunking
curl.exe -X POST "http://localhost:8080/api/v1/documents/1/process"

# 생성된 Chunk 조회
curl.exe "http://localhost:8080/api/v1/documents/1/chunks"

# Chunk를 1536차원 Embedding으로 변환해 pgvector에 저장
curl.exe -X POST "http://localhost:8080/api/v1/documents/1/embeddings"

# 벡터 배열을 제외한 Embedding 처리 상태 조회
curl.exe "http://localhost:8080/api/v1/documents/1/embeddings"

# 제목 변경
curl.exe -X PATCH "http://localhost:8080/api/v1/documents/1" `
  -H "Content-Type: application/json" `
  -d '{"title":"변경한 제목"}'

# 문서 삭제
curl.exe -X DELETE "http://localhost:8080/api/v1/documents/1"
```

- 지원 확장자: `.txt`, `.md`
- 문자 인코딩: UTF-8
- 파일 크기 제한: 1MB
- 기본 Chunk 크기: 600 tokens, 문서당 최대 500 chunks
- Chunking 처리 가능 상태: `PENDING`, `FAILED` (`FAILED` 재처리 시 `retryCount` 증가)
- Embedding 상태: `NOT_STARTED → PENDING → PROCESSING → READY/FAILED`
- Embedding batch 크기: 기본 32개, 실패 시 일부 벡터를 저장하지 않음
- 같은 원본 바이트의 SHA-256이 이미 등록되어 있으면 `409 Conflict`
- 오류 응답: `application/problem+json`과 식별 가능한 `code` 속성

## Retrieval API

질문을 기존 Embedding 모델의 1536차원 벡터로 변환하고, Chunking과 Embedding이 모두 `READY`인 Chunk에서 pgvector cosine exact search를 실행합니다. HNSW 등의 근사 인덱스는 사용하지 않습니다.

전체 흐름을 IntelliJ에서 한 단계씩 확인하려면 [Retrieval 역주행 수동 실습](docs/retrieval-lab-results.md)을 열고 [실행 요청 모음](http/retrieval-lab.http)을 위에서 아래로 실행합니다. 실제 OpenAI 호출은 비용과 학습 흐름을 통제할 수 있도록 일괄 자동화하지 않았습니다.

```powershell
curl.exe -X POST "http://localhost:8080/api/v1/search" `
  -H "Content-Type: application/json" `
  -d '{"query":"DB connection timeout은 무엇을 확인해야 하나요?","topK":5,"minimumSimilarity":0.0}'
```

- `query`: 앞뒤 공백을 제거한 후 1~2,000자
- `topK`: 1~20, 기본값 5
- `minimumSimilarity`: -1.0~1.0, 기본값 0.0
- 현재 Query Embedding과 provider, model, dimensions가 같은 Chunk만 검색
- 검색 결과가 없으면 `200 OK`와 빈 `results` 반환
- API Key 없이 기본 부팅은 가능하지만 검색 요청은 `503 EMBEDDING_DISABLED` 반환
- query, query vector, API Key는 로그나 Trace attribute에 기록하지 않음

## RAG Answer API

검색된 Chunk를 `[S1]`, `[S2]` Source로 조립한 뒤 Generation 모델이 근거가 포함된 답변을 생성합니다. 실제 Retrieval Source는 LLM 본문과 별도로 반환하며, 존재하지 않는 Source ID나 인용 없는 답변은 `502 GENERATION_INVALID_RESPONSE`로 거부합니다.

Retrieval 실습을 먼저 완료한 다음 [RAG 수동 실습 기록지](docs/rag-lab-results.md)를 열고 [RAG 실행 요청 모음](http/rag-lab.http)을 위에서 아래로 실행합니다.

```powershell
curl.exe -X POST "http://localhost:8080/api/v1/answers" `
  -H "Content-Type: application/json" `
  -d '{"question":"DB 연결을 제때 얻지 못할 때 무엇을 확인해야 하나요?","topK":5,"minimumSimilarity":0.5}'
```

- `question`: 앞뒤 공백을 제거한 후 1~2,000자
- `topK`: 1~20, 기본값 5
- `minimumSimilarity`: -1.0~1.0, 답변용 기본값 0.5
- 결과가 없으면 LLM을 호출하지 않고 `제공된 문서만으로는 확인할 수 없습니다.` 반환
- Context 전문은 모델에 전달하지만 공개 Source에는 최대 500자의 excerpt만 반환
- API Key 없이 기본 부팅은 가능하지만 답변 요청은 `503 GENERATION_DISABLED` 반환
- 질문, Context, Prompt, API Key는 로그나 Trace attribute에 기록하지 않음

## 환경변수

로컬 기본값은 `.env.example`에 정의되어 있습니다. `.env`는 개발자별 실제 값과 비밀정보를 담는 파일이며 Git에서 제외됩니다.

### PostgreSQL 컨테이너

| 변수 | 필수 여부 | 기본값 | 용도 | 민감정보 |
|---|---|---|---|---|
| `POSTGRES_DB` | 선택 | `rag_observability` | 컨테이너가 생성할 DB 이름 | 아니요 |
| `POSTGRES_USER` | 선택 | `rag_app` | 컨테이너 DB 사용자 | 아니요 |
| `POSTGRES_PASSWORD` | 로컬 실행 시 필수 | `rag_local_password` | 컨테이너 DB 비밀번호 | 예 |
| `POSTGRES_PORT` | 선택 | `5432` | 호스트에 공개할 PostgreSQL 포트 | 아니요 |

### Spring Datasource

| 변수 | 필수 여부 | 기본값 | 용도 | 민감정보 |
|---|---|---|---|---|
| `DB_HOST` | 선택 | `localhost` | Spring Boot가 연결할 DB 호스트 | 아니요 |
| `DB_PORT` | 선택 | `5432` | Spring Boot가 연결할 DB 포트 | 아니요 |
| `DB_NAME` | 선택 | `rag_observability` | Spring Boot가 사용할 DB 이름 | 아니요 |
| `DB_USER` | 선택 | `rag_app` | Spring Datasource 사용자 | 아니요 |
| `DB_PASSWORD` | 로컬 실행 시 필수 | `rag_local_password` | Spring Datasource 비밀번호 | 예 |

### AI 모델 예약값

| 변수 | 필수 여부 | 기본값 | 용도 | 민감정보 |
|---|---|---|---|---|
| `SPRING_AI_MODEL_CHAT` | 선택 | `none` | Generation Gateway provider (`none` 또는 `openai`) | 아니요 |
| `SPRING_AI_MODEL_EMBEDDING` | 선택 | `none` | Embedding Gateway provider (`none` 또는 `openai`) | 아니요 |
| `OPENAI_API_KEY` | AI 활성화 시 필수 | 빈 값 | OpenAI API 인증 | 예 |
| `OPENAI_BASE_URL` | 선택 | `https://api.openai.com` | OpenAI 호환 API base URL | 아니요 |
| `CHAT_MODEL` | 선택 | `gpt-4.1-mini` | 답변 생성에 사용할 모델 예약값 | 아니요 |
| `EMBEDDING_MODEL` | 선택 | `text-embedding-3-small` | Embedding에 사용할 모델 예약값 | 아니요 |
| `EMBEDDING_DIMENSIONS` | 선택 | `1536` | 모델 벡터와 pgvector 컬럼의 차원 | 아니요 |
| `RAG_EMBEDDING_BATCH_SIZE` | 선택 | `32` | 한 번의 공급자 요청에 포함할 Chunk 수 | 아니요 |
| `RAG_EMBEDDING_CONNECT_TIMEOUT` | 선택 | `3s` | Embedding 공급자 연결 제한 시간 | 아니요 |
| `RAG_EMBEDDING_READ_TIMEOUT` | 선택 | `30s` | Embedding 공급자 응답 제한 시간 | 아니요 |
| `RAG_RETRIEVAL_TOP_K` | 선택 | `5` | Vector Search의 기본 최대 결과 수 | 아니요 |
| `RAG_RETRIEVAL_MIN_SIMILARITY` | 선택 | `0.0` | Vector Search의 기본 최소 cosine similarity | 아니요 |
| `RAG_GENERATION_CONNECT_TIMEOUT` | 선택 | `3s` | 답변 생성 공급자 연결 제한 시간 | 아니요 |
| `RAG_GENERATION_READ_TIMEOUT` | 선택 | `60s` | 답변 생성 공급자 응답 제한 시간 | 아니요 |
| `RAG_GENERATION_MAX_OUTPUT_TOKENS` | 선택 | `600` | 답변 생성 최대 출력 token | 아니요 |
| `RAG_ANSWER_MINIMUM_SIMILARITY` | 선택 | `0.5` | RAG 답변에 사용할 최소 검색 유사도 | 아니요 |
| `RAG_SOURCE_EXCERPT_LENGTH` | 선택 | `500` | 답변 API Source excerpt 최대 문자 수 | 아니요 |

### OpenTelemetry

| 변수 | 필수 여부 | 기본값 | 용도 | 민감정보 |
|---|---|---|---|---|
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | 선택 | `http://localhost:4318/v1/traces` | Spring Boot Trace를 전송할 Collector OTLP/HTTP 주소 | 아니요 |
| `OTEL_TRACES_SAMPLER_PROBABILITY` | 선택 | `1.0` | 로컬 Trace 샘플링 비율 | 아니요 |

### Document Chunking

| 변수 | 필수 여부 | 기본값 | 용도 | 민감정보 |
|---|---|---|---|---|
| `RAG_CHUNK_SIZE_TOKENS` | 선택 | `600` | TokenTextSplitter의 목표 Chunk 크기 | 아니요 |
| `RAG_MAX_CHUNKS_PER_DOCUMENT` | 선택 | `500` | 한 문서에서 저장할 수 있는 최대 Chunk 수 | 아니요 |

## AI 설정

실제 Embedding과 답변 생성을 검증할 때만 `.env`에서 아래 값을 활성화합니다. `.env`는 Git에 커밋되지 않습니다.

```dotenv
SPRING_AI_MODEL_CHAT=openai
SPRING_AI_MODEL_EMBEDDING=openai
OPENAI_API_KEY=실제_키
```

API 키, 원문 질문, Chunk 전문과 Prompt 전문은 로그나 Trace attribute에 기록하지 않습니다.

## 프로젝트 범위

### MVP 필수

- TXT/MD 문서 등록, SHA-256 중복 방지와 처리 상태·실패 재시도
- Chunking과 Embedding 생성·저장
- pgvector cosine exact search, Top-K와 minimum similarity
- 실제 Source ID가 연결된 RAG 답변과 근거 부족 질문의 명시적 거절
- Golden Query 기반 Hit@K·MRR·p50/p95 평가
- OpenTelemetry Trace와 Chunk Size × Top-K 재현 실험

### MVP 권장 품질

- Testcontainers 기반 DB 통합 테스트와 Fake Gateway 단위 테스트
- 파일 크기·Chunk 수·문자열 길이 등 입력 제한
- PostgreSQL 키워드 검색과 Vector Search baseline 비교
- Retrieval/E2E latency, Context token과 요청당 예상 비용 측정

### MVP 이후 확장 후보

- PDF 텍스트 추출
- 인증·Workspace와 데모 UI
- HNSW, Hybrid Search와 Reranker
- Prometheus Metrics와 Loki Logs

### 이번 프로젝트에서 제외

- Kubernetes, Kafka와 Redis
- 자체 Embedding/LLM 모델 서빙
- 멀티에이전트
- PDF OCR
- 여러 AI Provider의 실제 동시 구현

## 현재 구현 상태

개발 환경과 0단계 기준 결정·문서화, Document CRUD, Chunking, Embedding, Retrieval과 RAG 답변 생성까지 완료한 상태입니다.

- Maven/Java 21 프로젝트 뼈대
- pgvector DB와 Flyway 초기 스키마
- Actuator health
- OTel Collector → Tempo → Grafana 로컬 스택
- OpenAI 모델을 나중에 안전하게 활성화할 환경변수
- Windows 실행/검증 스크립트
- MVP 범위, 환경변수 표와 네 개의 ADR
- TXT/Markdown 업로드와 PostgreSQL 원문 저장
- 문서 목록·상세 조회, 제목 변경과 cascade 삭제
- SHA-256 중복 방지, 입력 제한과 ProblemDetail 오류 응답
- Testcontainers 기반 Document API 통합 테스트
- Spring AI TokenTextSplitter 기반 600-token Chunking과 token count 저장
- `PENDING → PROCESSING → READY/FAILED` 상태 전이와 실패 재시도
- 조건부 상태 선점, batch insert와 부분 Chunk rollback 검증
- 독립된 Embedding 상태·오류·재시도 모델과 동시 처리 방지
- OpenAI/Disabled/Fake `EmbeddingGateway` 분리와 API Key 없는 기본 부팅
- 32개 단위 batch 호출, 1536차원·유한값·non-zero 검증
- pgvector 직접 SQL 저장과 실패 시 전체 vector rollback
- Query Embedding과 provider/model/dimensions가 일치하는 Chunk만 검색
- pgvector cosine exact search, Top-K와 minimum similarity 필터
- 재현 가능한 tie-break 정렬과 vector를 노출하지 않는 `/api/v1/search` 응답
- OpenAI/Disabled/Fake `GenerationGateway`와 API Key 없는 기본 부팅
- `[S1]` 형식의 Context 조립, 실제 Retrieval Source의 별도 반환
- 근거 없는 질문의 생성 호출 생략과 결정적인 거절 응답
- 빈 답변·인용 누락·존재하지 않는 Source ID의 엄격한 검증
- 생성 connect/read timeout, 단일 호출 정책과 token 사용량 반환
- `/api/v1/answers`의 Testcontainers 기반 전체 파이프라인 통합 테스트

Golden Query baseline 측정과 검색·RAG Custom Span 다음 순서는 threshold 및 Chunk Size × Top-K 실험입니다.

## 검색·RAG Custom Span (7장)

`/api/v1/search`와 `/api/v1/answers` 응답의 `X-Trace-Id`로 Grafana Tempo에서 요청을 찾을 수 있습니다. 검색의 Query Embedding·DB 검색, 답변의 Context 조립·Generation·인용 검증 시간이 각각 기록됩니다. 근거 부족은 `rag.outcome=no_evidence`로 표시되며 Generation Span을 만들지 않습니다.

실행 요청은 `http/observability-lab.http`, Grafana 조회 방법과 무료 Fake 검증 명령은 [관측성 실습](docs/observability-lab.md)에 있습니다. tracing 비활성화 시 헤더와 Custom Span은 생략하며 Golden Query 평가 프로필은 계속 tracing을 끕니다. 질문·Prompt·원문·벡터·키·원래 예외 내용은 Span에 기록하지 않습니다.

## Golden Query baseline (6장)

검색 품질을 문서 수준으로 채점합니다. LLM 답변 품질 평가는 아닙니다.

1. `evaluation/retrieval-golden-set.json`: 정답 파일명으로 고정한 15개 질문입니다. 문서마다 DIRECT 2개·PARAPHRASED 2개, UNRELATED 3개입니다. 힌트는 사람의 검토용이며 자동 채점에 사용하지 않습니다.
2. `EvaluationMetrics`: Top-5 **Chunk 순위**를 유지하여 Hit@5와 MRR@5를 계산합니다. 같은 문서의 Chunk를 중복 제거하지 않습니다. 관련 없는 질문은 분모에서 제외합니다.
3. `EvaluationCorpus` → `EvaluationRunner`: 기존 DB를 사전 검사한 후 `RetrievalService.search()`를 순차 호출합니다. 외부 호출을 감싸는 DB 트랜잭션은 없습니다.
4. `EvaluationReport`: 실행별 고유 디렉터리에 결과와 재현 조건을 보관합니다. 중간 실패는 이후 호출을 중단하고 `INCOMPLETE`로 기록하며 최종 Hit/MRR은 발표하지 않습니다.

### 실행

Docker PostgreSQL을 실행하고 기존 lab의 세 문서가 Chunking/Embedding 모두 READY인지 확인합니다. `.env`에 활성 Embedding provider와 유효한 API Key가 필요합니다. 키를 명령행에 입력하지 마세요.

```powershell
# 사용법만 표시: API 호출 없음
.\scripts\run-evaluation.ps1

# 유료 Query Embedding 호출에 명시적으로 동의할 때 실행
.\scripts\run-evaluation.ps1 -Live
```

Spring 서버는 따로 실행하지 않아도 됩니다. 전용 non-web `evaluation` 프로필이 Generation, Flyway, tracing을 끄고 실행 후 종료합니다. 정상 완료는 종료 코드 0, 사전 검사·검색·출력 실패는 비정상 종료입니다. 일반 부팅과 `mvn clean test`는 평가를 자동 실행하지 않습니다.

- 조건: Top-K=5, minimum similarity=-1.0, 기존 cosine exact search.
- 기존 600-token Chunk를 재사용합니다. **600이라는 생성 설정은 DB에 저장되어 있지 않으므로 운영자 확인 사항**이며, 보고서에 이 전제를 명시합니다. Chunk 수는 실제 DB에서 기록하며 13개로 강제하지 않습니다.
- 세 파일명이 각각 유일하고 모든 Chunk가 현재 provider/model의 1536차원 벡터를 가져야 합니다. 추가 적격 문서가 있으면 baseline 오염을 막기 위해 중단합니다. 실행 중 업로드·삭제·재처리를 하지 마세요.
- 정상 실행은 논리 검색 호출 15회입니다. 재임베딩·LLM·워밍업·자동 재시도는 없습니다.

### 결과 읽기

`evaluation/results/<timestamp>-<runId>/`에 `queries.csv`, `hits.csv`, `summary.md`가 생성됩니다. Git commit/dirty, 데이터셋 SHA-256, 문서 ID·원문 해시·Chunk 수, 모델과 검색 조건을 함께 남깁니다. 결과 디렉터리는 Git에서 제외됩니다. 포트폴리오에 게시할 결과는 검토한 뒤 별도로 보관하세요.

- **Hit@5**: 관련 질문 12개 중 정답 문서의 Chunk가 Top-5에 한 번이라도 등장한 비율.
- **MRR@5**: 첫 정답 Chunk의 순위가 1위면 1, 3위면 1/3, 없으면 0인 점수의 평균.
- **p50/p95**: 성공 요청의 Query Embedding + DB 검색 시간에 nearest-rank 적용. HTTP·LLM·파일 출력은 제외하며, 작은 표본의 탐색적 측정이지 부하 테스트가 아닙니다.
- `REVIEW_RANK`/`MISS` 행은 원문과 `relevantChunkHint`를 참고하여 검토합니다. 문서가 맞아도 해당 Chunk에 충분한 근거가 있다는 보장은 없습니다.
- UNRELATED는 Hit/RR을 비워 두고 최고 similarity와 결과 수를 관찰합니다. threshold=-1에서는 빈 결과를 기대하지 않습니다.
- 낮은 점수도 그대로 baseline으로 보관합니다. 질문/정답을 변경할 때는 원본을 보존하고 새 데이터셋 버전으로 기록하세요. 해시는 변경 식별에 사용됩니다.

사전 검사 실패 코드: `EMBEDDING_DISABLED`(설정), `CORPUS_MISSING_OR_DUPLICATE_FILENAME`(파일명), `CORPUS_NOT_READY_OR_MODEL_MISMATCH`(상태·Chunk·벡터), `EXTRA_ELIGIBLE_DOCUMENTS`(추가 검색 대상). 원문·벡터·키·provider 내부 오류는 보고서에 기록하지 않습니다.

자동 테스트는 Fake Gateway와 별도 Testcontainers DB로 검증합니다. 실제 baseline 완료는 사용자가 `-Live`로 15개 결과를 생성한 뒤 판정합니다. threshold 비교·LLM 평가·Custom Span·Frontend 및 RAG Prompt Injection 수동 검증은 이번 범위에 포함되지 않습니다.

## 저장된 Baseline Threshold 비교 (8-1장)

```powershell
.\scripts\compare-thresholds.ps1 -BaselineDir "evaluation/results/<기존 실행 디렉터리>"
```

Spring·Docker·DB·API Key 없이 Java 21로 저장된 결과만 분석합니다. 최초 Maven 의존성 준비에는 인터넷이 필요할 수 있지만 OpenAI 호출은 없습니다. 경로는 명령을 실행한 위치 기준이며 필수입니다. `.env`를 읽거나 현재 RAG threshold를 변경하지 않습니다.

입력은 `summary.md`, `queries.csv`, `hits.csv`입니다. COMPLETE·Top-K=5·threshold=-1.0·관련 12개/무관 3개의 정상 baseline만 받습니다. 손상된 CSV, 누락 메타데이터, 중복 ID, 비정상 순위·점수·결과 수는 실행 전에 거부합니다. 정답 파일명은 입력 실행 기록에서 가져옵니다.

threshold `-1.0 / 0.3 / 0.4 / 0.5 / 0.7`에서 Hit@5·MRR@5·관련 질문 빈 결과율·무관 질문 결과 반환율·평균 Chunk 수를 비교합니다. 같은 문서의 여러 Chunk도 순위에 그대로 남습니다. 기준 점수와 같으면 포함합니다.

출력은 `evaluation/results/threshold-comparison-<timestamp>-<runId>/`에 보관합니다.

- `comparison.csv`: 조건별 지표와 분모
- `query-comparison.csv`: 15개 질문 × 5개 조건, 정답 누락과 빈 결과 구분
- `summary.md`: 비교표·탈락 질문·입력 파일 해시·원래 실행 조건·비교 도구 Git 상태

정상 종료 코드는 0, 입력·출력 실패는 비정상 종료입니다. 성공 summary는 모든 결과를 쓴 뒤 생성합니다. 원본과 이전 실행 결과는 덮어쓰지 않습니다. 보고서 디렉터리는 기존처럼 Git에서 제외합니다.

현재 저장된 baseline 재분석 결과: threshold 0.3에서는 Hit@5=1.0, MRR@5=0.875를 유지하며 무관 질문 3개가 모두 빈 결과입니다. 0.4는 Hit@5=0.75, 0.5는 0.5, 0.7은 약 0.0833입니다. 0.4에서 DEPLOY-004는 다른 문서가 남지만 정답은 사라집니다. 결과가 비지 않았다는 사실만으로 검색 성공으로 판정하지 않습니다.

이는 기존 Top-5의 더 높은 threshold에 대한 재분석입니다. Top-K를 늘리거나 새로운 순위·지연시간을 측정하지 않습니다. 후보를 선택한 뒤 새로운 질문으로 검증해야 하며 자동으로 최적값을 확정하지 않습니다.

관련 테스트: `ThresholdComparisonTests` (개발 DB와 실제 baseline 파일에 의존하지 않는 생성 fixture 사용).
# 새 질문으로 Threshold 후보 검증

18개 고정 질문의 무료 사전 검증, 별도 유료 평가(Embedding 18회), 저장 결과 재채점 절차는 [threshold 검증 안내](docs/threshold-validation.md)를 참고하세요. 기존 15개 baseline과 RAG threshold 0.5는 유지합니다.
