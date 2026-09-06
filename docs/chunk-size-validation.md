# Chunk Size 비교 실습: 300 / 600 / 900

## 이번 실험의 목적

같은 질문에 대해 문서를 얼마나 잘게 나누는 것이 적절한지 비교합니다. Top-K=5, threshold=0.3과 모델은 고정하고 분할 크기만 바꿉니다. 운영 기본값 600 / Top-K 5 / RAG threshold 0.5는 변경하지 않습니다.

1. 무료 검사에서 실제 Splitter로 모든 조건을 분할하고 호출 수를 계산합니다.
2. 크기마다 새 임시 PostgreSQL 컨테이너를 만들고 기존 업로드·Chunking·Embedding 서비스를 실행합니다.
3. 질문 벡터 18개는 한 번 생성해 세 조건에서 동일하게 재사용합니다. 캐시 누락 시 API로 재호출하지 않습니다.
4. 기존 RetrievalService와 exact SQL로 총 54번 검색하고 별도 보고서를 저장합니다.

## 1. 무료 사전 검사

프로젝트 루트 PowerShell에서:

```powershell
.\scripts\compare-chunk-sizes.ps1 -ValidateOnly
```

Java 21·Maven이 필요합니다. 최초 라이브러리 다운로드는 가능하지만 Spring context·Docker·DB·API Key는 필요 없습니다. test-scope Java main을 실행하며 테스트 전체를 실행하는 명령은 아닙니다.

현재 고정된 원문에서는 다음 결과가 나옵니다.

| Size | 전체 Chunk | 문서 Embedding batch |
|---|---:|---:|
| 300 | 25 | 3 |
| 600 | 13 | 3 |
| 900 | 9 | 3 |

질문 18회 + 문서 9회 = **총 API 호출 27회**, Generation 0회입니다. 설정 Size와 추정 token 수가 항상 같거나 엄격한 상한인 것은 아닙니다. 실제 300 조건에는 tokenCount 301인 Chunk도 있습니다. 모든 통계는 실제 추정값으로 기록합니다.

옵션 없이 실행해도 무료 검사만 합니다. `-Live`와 `-ValidateOnly` 동시 지정은 오류입니다.

## 2. 실제 유료 실험

Docker Desktop을 실행하고 `.env`의 유효한 `OPENAI_API_KEY`를 직접 준비합니다. 키를 채팅·명령행에 넣지 마세요. 모델은 실험 context에서 openai / text-embedding-3-small / 1536, batch 32로 고정하며 기존 timeout 설정을 사용합니다. Generation은 항상 비활성화합니다.

```powershell
.\scripts\compare-chunk-sizes.ps1 -Live
```

기존 앱 서버와 Compose DB를 켜 둘 필요는 없습니다. 실험 DB 연결은 Testcontainers가 새로 만든 임의 포트와 계정으로 강제하며 기존 DB로 fallback하지 않습니다. 각 DB에 Flyway V1~V3를 적용합니다. 영구 볼륨을 사용하지 않고 크기별 context·컨테이너를 종료합니다. 기존 DB의 데이터는 삭제하거나 수정하지 않습니다.

600도 새로 처리합니다. 과거 벡터나 점수를 새 실험에 섞지 않습니다. 질문 Embedding은 실행 중 메모리에만 저장하고 결과 파일에는 벡터를 남기지 않습니다.

## 3. 결과 확인

콘솔의 `Chunk size experiment COMPLETE` 뒤 경로를 확인합니다.

- `run.json`: 고정 데이터셋·문서 해시·모델·컨테이너 ID·Git 상태·시도/성공 호출 수.
- `comparison.csv`: 세 크기의 Hit/MRR, 유형별 지표·분모, Chunk 수·token 분포.
- `queries.csv`: 3 × 18 = 54개 검색 채점, 반환 token 합계.
- `hits.csv`: 검색 순위·파일명·Chunk ID/index·similarity·token 수.
- `chunks.csv`: 실제 저장된 47개 Chunk의 파일명·순서·내용 해시·token 수. 전문 제외.
- `summary.md`: 전체 비교표, 600 대비 변화, 정답 누락/빈 결과 목록과 한계.

token 분포는 min·평균·nearest-rank p50/p95·max·합계입니다. 미완료 보고서의 사전 분할 통계는 아직 DB에 저장되지 않은 조건도 포함하므로, 실제 저장 범위는 chunks.csv와 completedSizes를 확인합니다.

provider 성공 횟수는 Gateway가 결과를 반환한 횟수입니다. 잘못된 벡터가 반환되면 이후 Validator가 거부할 수 있습니다. 질문·문서 시도 횟수와 성공 횟수를 구분해서 읽으세요.

## 실패 및 재실행

오류가 발생하면 이후 호출을 중단하고 `INCOMPLETE`, 안전한 오류 코드, 실패 구간과 대상을 남깁니다. 부분 결과는 보관하지만 최종 비교표는 발표하지 않습니다. 정상 예외 종료에서도 컨테이너를 정리합니다.

강제 종료 시 Testcontainers Ryuk가 정리를 담당합니다. Docker 자체 장애로 남아 있다면 run.json의 컨테이너 ID 및 `rag.chunk-experiment` label로 해당 실행을 식별하세요. 프로젝트 Compose 컨테이너를 삭제하지 마세요.

재실행은 처음부터 새 폴더·새 DB에서 시작합니다. 자동 재개·재시도는 없고 **비용도 다시 발생**합니다. 컨테이너 준비 실패는 해당 조건의 API 호출 전에 중단되지만, 뒤 조건에서 실패하면 앞 조건에 이미 사용한 비용은 되돌릴 수 없습니다.

## 결과 해석

정답 문서가 검색됐다는 것은 그 Chunk에 질문의 답이 충분히 들어 있다는 보장이 아닙니다. 크기별 Chunk index도 서로 다른 내용이므로 같은 번호끼리 동일 근거로 취급하지 않습니다.

Token 수는 추정치이며 실제 과금·LLM Prompt token 수가 아닙니다. 질문 벡터를 재사용했으므로 과거 전체 검색 latency와 비교하지 않습니다. 이번에는 LLM 생성과 답변 품질 평가는 하지 않습니다.

구현 완료와 실제 품질 실험 완료는 다릅니다. Fake 테스트의 점수는 품질 증거가 아니며, 실제 Live 결과에서 가장 좋은 값이 나와도 운영 설정을 자동 변경하지 않습니다.
