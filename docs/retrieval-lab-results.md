# Retrieval 역주행 수동 학습·검증 기록지

이 실습의 목표는 완성된 API를 한 번에 믿는 것이 아니라, 원문이 Chunk와 vector로 바뀌고 질문에 가까운 Chunk가 순위로 돌아오는 과정을 단계별로 확인하는 것이다. 실제 요청은 [IntelliJ HTTP 실행 파일](../http/retrieval-lab.http)의 위쪽부터 한 개씩 실행한다.

## 0. 시작 전 보안 확인

> 과거 진단 출력에 노출된 OpenAI API Key는 사용하지 않는다. OpenAI 대시보드에서 폐기하고 새 키를 발급한 다음 Git에서 제외된 `.env`에만 입력한다.

- [ ] 기존 노출 키 폐기
- [ ] 새 키 발급
- [ ] `.env` 밖의 README, `.http`, 채팅 또는 로그에 키를 복사하지 않음
- [ ] `.env`에 `SPRING_AI_MODEL_EMBEDDING=openai` 설정
- [ ] `.env`에 `EMBEDDING_MODEL=text-embedding-3-small` 설정
- [ ] `.env`에 `EMBEDDING_DIMENSIONS=1536` 설정
- [ ] `.env`에 `RAG_CHUNK_SIZE_TOKENS=600` 설정
- [ ] `.env`에 `RAG_RETRIEVAL_TOP_K=5` 설정
- [ ] `.env`에 `RAG_RETRIEVAL_MIN_SIMILARITY=0.0` 설정

키 값은 이 문서에 기록하지 않는다.

```dotenv
SPRING_AI_MODEL_EMBEDDING=openai
OPENAI_API_KEY=새로_발급한_키
EMBEDDING_MODEL=text-embedding-3-small
EMBEDDING_DIMENSIONS=1536
RAG_CHUNK_SIZE_TOKENS=600
RAG_RETRIEVAL_TOP_K=5
RAG_RETRIEVAL_MIN_SIMILARITY=0.0
```

`.env`에서 OpenAI가 활성화된 동안 일반 `mvn clean test`를 실행하면 비활성 provider를 전제로 한 테스트가 로컬 설정의 영향을 받을 수 있다. 실습 도중 회귀 테스트가 필요하면 현재 PowerShell 프로세스에서만 아래처럼 실제 호출을 차단한다. 이 명령은 `.env` 파일을 수정하지 않는다.

```powershell
$env:SPRING_AI_MODEL_EMBEDDING='none'
$env:OPENAI_API_KEY='test-only-disabled'
.\mvnw.cmd clean test
```

애플리케이션을 실제 OpenAI 설정으로 실행할 터미널과 테스트 터미널은 별도로 연다.

## 1. 실행 준비

PowerShell에서 인프라 상태를 확인하고 애플리케이션을 실행한다.

```powershell
.\scripts\dev.ps1 status
.\scripts\run-app.ps1
```

`run-app.ps1`을 실행한 터미널은 그대로 둔다. IntelliJ에서 `http/retrieval-lab.http`를 열고 `1-1. 애플리케이션과 DB health 확인` 요청의 ▶ 버튼을 누른다.

| 확인 항목 | 기대값 | 관찰값 |
|---|---|--------|
| HTTP status | 200 | 200    |
| 전체 health | UP | UP     |
| DB component | UP | UP     |
| Flyway | V3 validation 성공 | O      |
| 로그의 API Key 값 | 노출 없음 | O      |

이 단계가 실패하면 Upload로 넘어가지 않는다. 애플리케이션 로그에는 키 자체를 적지 말고 오류 코드와 요약만 기록한다.

## 2. Upload 관찰

HTTP 파일에서 세 Upload 요청을 차례대로 실행한다. 성공한 응답의 `id`는 response handler가 현재 IntelliJ 세션의 `dbDocumentId`, `cpuDocumentId`, `deployDocumentId`에 저장한다. IntelliJ를 재시작하면 저장값이 사라지므로 같은 세션에서 계속하거나 목록 API에서 ID를 찾아 HTTP 파일 상단의 `0`을 수정한다.

| 문서 | documentId | HTTP | status | embeddingStatus | sourceType |
|---|-----------:|---:|---|---|---|
| DB Connection Timeout |          1 | 201 | PENDING | NOT_STARTED | MARKDOWN |
| CPU Spike |          2 | 201 | PENDING | NOT_STARTED | MARKDOWN |
| Deployment Rollback |          3 | 201 | PENDING | NOT_STARTED | MARKDOWN |

관찰 포인트:

- Upload 직후 PostgreSQL의 `document.raw_content`에는 원문이 있지만 `document_chunk`에는 아직 행이 없다.
- `status=PENDING`은 Chunking 대기 상태이고 `embeddingStatus=NOT_STARTED`는 Embedding을 시작할 수 있는 Chunk가 아직 없다는 뜻이다.
- 선택 요청인 중복 업로드를 실행하면 같은 원본 바이트의 SHA-256 때문에 `409 Conflict`가 나와야 한다.

## 3. Chunking 관찰

세 문서의 `/process` 요청을 차례대로 실행한 뒤 각 `/chunks` 조회를 실행한다. 문서가 두 개 미만의 Chunk로 나뉘면 이후 단계로 넘어가지 말고 샘플 문서를 더 길게 만든 뒤 기존 문서를 삭제하고 다시 시작한다.

작성 시점에 프로젝트의 실제 `TokenTextSplitter`와 `JTokkitTokenCountEstimator`로 수행한 오프라인 사전 검증에서는 DB 문서 5개, CPU 문서 4개, 배포 문서 4개 Chunk가 생성됐다. 수동 실행 결과도 이 값과 같은지 비교하되, 라이브러리나 설정을 변경했다면 실제 API 응답을 기준으로 기록한다.

| 문서 | chunkCount | Chunk별 tokenCount                  | index가 0부터 연속인가 | status | embeddingStatus |
|---|-----------:|-------------------------------------|------------------------|---|---|
| DB Connection Timeout |          5 | 554<br/>568<br/>548<br/>591<br/>161 | Y                      | READY | PENDING |
| CPU Spike |          4 | 595<br/>566<br/>596<br/>356         | Y                      | READY | PENDING |
| Deployment Rollback |          4 | 574<br/>572<br/>571<br/>414           | Y                      | READY | PENDING |

관찰 포인트:

- Chunk 내용은 원문의 순서를 유지하지만 경계에서는 문장이 자연스럽게 갈라질 수 있다.
- `tokenCount`는 글자 수가 아니라 모델이 처리하는 token 단위의 크기다.
- Chunking이 완료되면 문서 `status`는 `READY`, `embeddingStatus`는 `PENDING`이 된다.
- 아직 vector가 없으므로 모든 Chunk의 `embedded`는 `false`다.

## 4. Embedding 관찰

세 Embedding 요청은 실제 OpenAI API를 호출한다. 한 요청이 성공했는지 확인한 뒤 다음 문서로 이동한다. 인증 실패, timeout 또는 잘못된 차원 오류가 나면 Retrieval을 실행하지 않는다.

| 문서 | totalChunkCount | embeddedChunkCount | provider | model | dimensions | 결과/오류 |
|---|----------------:|-------------------:|---|---|---:|---|
| DB Connection Timeout |               5 |                  5 | openai | text-embedding-3-small | 1536 |  |
| CPU Spike |               4 |                  4 | openai | text-embedding-3-small | 1536 |  |
| Deployment Rollback |               4 |                  4 | openai | text-embedding-3-small | 1536 |  |

관찰 포인트:

- `totalChunkCount`와 `embeddedChunkCount`가 같아야 일부만 저장된 불완전한 문서가 아니다.
- 1536개 숫자는 PostgreSQL `vector(1536)`에 저장되지만 Chunk API에는 노출되지 않는다.
- API에서 확인해야 할 것은 숫자 배열 자체가 아니라 `embedded=true`, provider, model과 dimensions다.
- 문서의 Chunking 상태와 Embedding 상태가 모두 `READY`여야 Retrieval 검색 후보가 된다.

### 선택: DB에서 집계만 확인

IntelliJ Database 창이나 `psql`에서 아래 읽기 전용 SQL을 실행하면 원문과 vector 값을 출력하지 않고 저장 상태만 확인할 수 있다.

```sql
SELECT d.id,
       d.title,
       d.status,
       d.embedding_status,
       COUNT(c.id) AS chunk_count,
       COUNT(c.embedding) AS embedded_chunk_count,
       MIN(vector_dims(c.embedding)) AS min_dimensions,
       MAX(vector_dims(c.embedding)) AS max_dimensions,
       MIN(c.embedding_provider) AS provider,
       MIN(c.embedding_model) AS model
FROM document AS d
LEFT JOIN document_chunk AS c ON c.document_id = d.id
GROUP BY d.id, d.title, d.status, d.embedding_status
ORDER BY d.id;
```

세 문서 모두 `chunk_count = embedded_chunk_count`, `min_dimensions = max_dimensions = 1536`이어야 한다.

## 5. Retrieval 결과 기록

각 검색 응답에서 순위, 문서 제목, Chunk index와 similarity를 복사한다. Chunk 전문 전체를 로그나 이 문서에 복사할 필요는 없다. 어떤 내용이 매칭 근거였는지만 짧게 요약한다.

### 질문별 판정표

| 질문 유형 | threshold | 1위 문서/Chunk           | 1위 similarity | 기대 결과 | 관찰 결과       |
|---|---:|--------------------------|---------------:|---|-----------------|
| 직접 DB 질문 | -1.0 | DB connection / 1        |          0.579 | DB 문서 Top-1 | 예상결과와 동일 |
| 어려운 표현 | -1.0 | cpu Spike / 8            |          0.462 | DB 문서 Top-3 | 예상결과와 동일 |
| CPU 질문 | -1.0 | cpu Spike / 6            |          0.582 | CPU 문서 Top-1 | 에상결과와 동일 |
| 관련 없음 | -1.0 | Deployment Rollback / 13 |          0.176 | 점수 분포 관찰 | 낮은 similarity |
| 관련 없음 | 0.0 | Deployment Rollback / 13 |          0.176 | -1.0보다 결과가 늘지 않음 | -1.0와 동일     |
| 관련 없음 | 0.5 | X                        |              X | 0.0보다 결과가 늘지 않음 | resultCount = 0 |
| 관련 없음 | 0.7 | X                        |              X | 결과 감소 또는 빈 결과 가능 | resultCount = 0 |

### 직접 DB 질문 Top-5

| 순위 | documentId | 문서 제목                        | chunkIndex | similarity | 매칭된 내용 요약 |
|---:|-----------:|----------------------------------|-----------:|-----------:|---|
| 1 |          1 | DB Connection                    |          0 |      0.579 | 첫 번째로 HikariCP의 `active`, `idle`, `pending`, `max` 지표를 확인한다. |
| 2 |          1 | DB Connection                    |          3 |      0.557 | 명확한 blocking transaction이 있고 종료 영향이 확인됐을 때만 해당 세션 취소 또는 종료를 선택한다. |
| 3 |          1 | DB Connection                    |          2 |      0.460 | 단순히 세션을 종료하기 전에 어떤 애플리케이션, 사용자, 쿼리와 요청에서 만들어졌는지 식별하고 영향 범위를 기록한다. |
| 4 |          1 | DB Connection                    |          4 |      0.452 | 재발 방지를 위해 풀 대기자 수, connection acquisition latency, `idle in transaction` 지속 시간에 경보를 둔다. |
| 5 |          3 | Deployment Rollback              |          1 |      0.402 | probe 실패 이유와 애플리케이션 시작 로그를 함께 확인해 단순 시간 부족인지 실제 의존성 실패인지 분류한다 |

### 어려운 표현 질문 Top-5

| 순위 | documentId | 문서 제목           | chunkIndex | similarity | 매칭된 내용 요약 |
|---:|-----------:|---------------------|-----------:|-----------:|---|
| 1 |          2 | cpu Spike           |          2 |      0.462 | 여러 dump에서 JSON 직렬화, 정규식, 압축, 암호화 또는 특정 반복문이 계속 상단에 보이는지 확인한다. lock을 기다리는 BLOCKED 스레드가 많다면 CPU 문제처럼 보이지만 실제 병목은 동기화 경합일 수 있다. |
| 2 |          1 | DB connection       |          2 |      0.438 | 단순히 세션을 종료하기 전에 어떤 애플리케이션, 사용자, 쿼리와 요청에서 만들어졌는지 식별하고 영향 범위를 기록한다 |
| 3 |          3 | Deployment Rollback |          1 |      0.416 | probe 실패 이유와 애플리케이션 시작 로그를 함께 확인해 단순 시간 부족인지 실제 의존성 실패인지 분류한다. |
| 4 |          2 | cpu Spike           |          1 |      0.404 | 단순히 JVM 프로세스의 CPU 백분율만 보면 이 차이를 놓칠 수 있으므로 Kubernetes request와 limit, 노드의 실제 여유까지 함께 본다. |
| 5 |          1 | DB connection       |          1 |      0.400 |작스러운 트래픽 증가 없이 배포 직후 문제가 시작됐다면 커넥션을 닫지 않는 코드, 트랜잭션 범위 확대, 새로 추가한 느린 쿼리를 우선 의심한다.  |

### CPU 질문 Top-5

| 순위 | documentId | 문서 제목     | chunkIndex | similarity | 매칭된 내용 요약                                                                                                                                     |
|---:|-----------:|---------------|-----------:|-----------:|------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 |          2 | cpu Spike     |          0 |      0.582 | 컨테이너가 CPU limit에 막혀 throttling 되는 경우, JVM의 GC가 반복되는 경우, 특정 스레드가 무한 반복하는 경우와 정상적인 트래픽 증가를 구분해야 한다. |
| 2 |          2 | cpu Spike     |          3 |      0.488 | GC pause와 allocation rate, runnable thread 수가 안정화됐는지 확인한다.                                                                              |
| 3 |          2 | cpu Spike     |          1 |      0.485 |오류를 만난 클라이언트가 빠르게 재시도하는지도 확인한다. 재시도 폭풍은 동일 작업을 여러 번 실행해 CPU를 소모하고 downstream에 추가 부하를 전달하므로 rate limit과 backoff 상태를 점검한다.                                                                                                                                                      |
| 4 |          1 | DB connection |          3 |      0.416 |명확한 blocking transaction이 있고 종료 영향이 확인됐을 때만 해당 세션 취소 또는 종료를 선택한다. |
| 5 |          2 | cpu Spike     |          2 |      0.411 |짧은 프로파일링이 허용되면 Java Flight Recorder나 async-profiler로 CPU sample을 수집하되 운영 부하와 수집 시간을 제한한다                                                                                                                                                                                                                                                                                                           |

### 관련 없는 질문의 threshold 변화

| threshold | resultCount | 최고 similarity | 최저 similarity | 관찰                 |
|---:|------------:|----------------:|----------------:|----------------------|
| -1.0 |           5 |           0.176 |           0.128 | 관련없는 내용들 제시 |
| 0.0 |           5 |           0.176 |           0.128 | -1.0와 동일          |
| 0.5 |           0 |               x |               x | 답변 안나옴          |
| 0.7 |           0 |               x |               x |답변 안나옴

관련 없는 질문에도 낮은 threshold에서는 결과가 나올 수 있다. Vector Search는 “정답이 있다”를 판단하는 기능이 아니라 현재 후보 가운데 상대적으로 가까운 것을 정렬하는 기능이기 때문이다. threshold를 높이면 조건을 통과하지 못한 후보가 제거되므로 결과 수는 같거나 줄어야 한다.

## 6. OpenAI 호출과 오류 기록

이 HTTP 파일의 필수 흐름은 문서 Embedding 3회와 Query Embedding 7회다. 각 문서가 32개 이하의 Chunk라면 현재 batch 크기에서 문서당 외부 Embedding 요청은 보통 1회이므로 예상 외부 호출은 총 10회다. Chunk가 32개를 넘거나 요청을 재실행하면 실제 호출 수는 늘어난다.

| 시각 | 작업 | 논리 호출 수 | 성공/실패 | HTTP/code | 비민감 오류 요약 |
|---|---|---:|-----------|-----------|---|
|  | 문서 Embedding 3건 | 3 | 3/0       | 200       |  |
|  | 직접 DB 질문 | 1 | 1/0       | 200       |  |
|  | 어려운 표현 질문 | 1 | 1/0       | 200       |  |
|  | CPU 질문 | 1 | 1/0       | 200       |  |
|  | 관련 없음, threshold 4종 | 4 | 4/0       | 200       |  |
| **합계** |  | **10** | 10/0      | 200       |  |

API Key, query vector, Chunk 전문 또는 SQL vector literal은 오류 기록에 넣지 않는다.

## 7. 이해 점검

다음 질문에 한두 문장으로 직접 답하면 실습 목적을 달성했는지 확인할 수 있다.

1. Upload 직후 `status=PENDING`, `embeddingStatus=NOT_STARTED`인 이유는 무엇인가?
2. Chunking 상태와 Embedding 상태를 하나로 합치지 않은 이유는 무엇인가?
3. 저장 Chunk와 질문이 같은 provider, model과 dimensions를 써야 하는 이유는 무엇인가?
4. 관련 없는 질문도 threshold가 낮으면 결과가 나오는 이유는 무엇인가?
5. `minimumSimilarity`를 올릴 때 resultCount가 늘면 안 되는 이유는 무엇인가?
6. Retrieval 응답에 vector 배열을 노출하지 않아도 검색이 동작했음을 어떻게 확인했는가?

## 8. 완료 기준

- [ ] 문서 3개 모두 `status=READY`, `embeddingStatus=READY`
- [ ] 각 문서가 2개 이상의 Chunk를 가짐
- [ ] 모든 Chunk의 `embedded=true`
- [ ] DB 집계에서 모든 vector가 1536차원
- [ ] 직접 DB 질문에서 DB 문서 Top-1
- [ ] CPU 질문에서 CPU 문서 Top-1
- [ ] 어려운 표현 질문에서 DB 문서 Top-3
- [ ] 관련 없는 질문이 관련 질문보다 낮은 similarity 분포를 보임
- [ ] threshold 증가에 따라 결과 수가 같거나 줄어듦
- [ ] 응답과 로그에 API Key와 vector 배열이 노출되지 않음
- [ ] OpenAI 호출 수와 오류를 기록함

검색 품질 항목 하나가 기대와 다르더라도 곧바로 구현 버그라고 단정하지 않는다. 순위와 similarity를 그대로 남긴 뒤 Chunk 경계, 질문 표현, 문서 내용과 threshold를 함께 분석한다. HTTP 오류, 상태 필터 누락 또는 차원 불일치는 기능 문제이고, 기대 문서가 2위로 내려간 것은 품질 평가 문제일 수 있다.

## 9. 종료와 다음 단계용 데이터 보존

검증이 끝나면 애플리케이션 터미널에서 `Ctrl+C`를 한 번 누르고 graceful shutdown 로그를 확인한다. 그다음 `.env`에서 새 API Key 값을 제거하고 `SPRING_AI_MODEL_EMBEDDING=none`으로 되돌린다. 이 변경 뒤 애플리케이션을 다시 시작하면 API Key 없이 부팅되지만 새 Embedding과 Retrieval 요청은 `503 EMBEDDING_DISABLED`가 정상이다.

HTTP 파일 맨 아래 DELETE 요청은 실행하지 않는다. PostgreSQL에 저장된 문서, Chunk와 vector는 다음 RAG 단계에서 재사용한다. 다음에 실제 검색을 다시 실행하려면 안전하게 보관한 새 키를 `.env`에 넣고 provider를 잠시 `openai`로 활성화해야 한다.
