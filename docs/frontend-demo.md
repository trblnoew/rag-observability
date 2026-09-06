# Frontend MVP 실행·시연

## 준비 및 무료 실행

Node.js 22.12 이상 또는 24 LTS와 npm이 필요하다. Node 설치 시 npm을 함께 설치하고 새 터미널에서 `node --version`, `npm --version`을 확인한다. Windows에 Edge가 있어야 아래 브라우저 테스트가 실행된다.

프로젝트 루트에서:

```powershell
cd frontend
npm ci
npm run dev
```

브라우저에서 `http://localhost:5173`을 연다. 기본 무료 데모는 Spring·Docker·API Key 없이 동작하며 백엔드 요청을 보내지 않는다. Node/npm을 찾지 못하면 Node 설치와 PATH를 확인한다. PowerShell이 npm.ps1을 차단하면 `npm.cmd ci`, `npm.cmd run dev`를 사용할 수 있다.

## 3분 시연

1. 문서 라이브러리: DB 문서 선택 → 원문 → Chunks. 원문과 조각, 실제 token 수의 차이를 살핀다. 데모의 READY/Embedded는 예시 상태다.
2. 검색·질문: 검색만 → 답변 생성 → `[S1]` 클릭. 검색 결과와 생성 답변을 구분한다. 연차 질문은 근거 부족·Generation 생략 예시다. 이 화면의 답변·점수는 **설명용 예시**, 실제 AI 생성 결과가 아니다.
3. 실험 결과: Threshold / Top-K / Chunk Size 탭을 비교한다. 이 화면은 검증된 **저장 실측 결과**다. 질문 선택으로 정답과 판단 근거를 확인한다. 작은 화면에서는 수치 표를 좌우로 스크롤한다.

낮은 threshold가 필요한 근거를 보존해도 답 없는 질문을 통과시킬 수 있다. 문서 수준 Hit가 높다고 답변 정확성까지 증명되지는 않는다. Chunk 반환 tokens는 과금량이 아니다. 운영 기본값 600 / 5 / 0.5는 변경되지 않는다.

## 로컬 실제 API 연결

별도 터미널에서 기존 `scripts/dev.ps1 up`, `scripts/run-app.ps1`로 백엔드를 준비한 후 화면의 연결 모드를 **로컬 API 연결**로 바꾼다. Vite가 `/api`와 health만 localhost:8080으로 전달한다. `.env`·키는 백엔드 전용이며 프론트에 입력하거나 복사하지 않는다.

- 문서 업로드 → 선택 → Chunking → Embedding. Embedding은 유료 호출 확인 후 실행한다.
- 검색은 Query Embedding 비용, 답변 생성은 추가 Generation 비용이 발생할 수 있다. 버튼 입력 전에는 요청하지 않는다.
- 503은 provider 비활성화, 409는 현재 상태 충돌, 502는 공급자 실패를 의미한다. 실패해도 데모로 바뀌지 않는다.
- 네트워크 오류 뒤 POST를 자동 재시도하지 않는다. 문서 상태를 먼저 새로고침한다. 화면을 닫아도 서버 작업은 취소되지 않는다.
- Trace ID가 있으면 복사해 Grafana Explore → Tempo에서 검색한다. 샘플링/전송/보존 상태 때문에 Trace가 없을 수 있다.
- 모드 전환으로 결과·선택 상태를 초기화한다. 질문·원문·답변을 localStorage에 보관하지 않는다.

이 서버는 로컬 데모용이다. 인증·비용 제한 없이 인터넷에 공개하지 않는다. `VITE_*` 변수에 API Key를 넣지 않는다.

## 무료 자동 검증

`frontend` 디렉터리:

```powershell
npm test
npm run build
npm run e2e
npm audit
```

브라우저 테스트는 Edge를 사용하고 5173 포트를 직접 시작·종료한다. 기존 dev 서버를 종료한 뒤 실행한다. 무료 데모/반응형/오류/인용/HTML 안전성/중복 요청을 확인한다. 실패 자료는 `frontend/test-results`에 남는다.

프로젝트 루트의 백엔드 회귀 및 무료 실제 HTTP 통합 테스트:

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd '-Dfrontend.e2e=true' '-Dtest=FrontendHttpIntegrationTests' test
```

두 번째 명령은 Docker와 설치된 frontend 의존성·Node·Edge가 필요하다. 5174 프론트 서버 → 임의 포트 Spring → 새 Testcontainers pgvector DB → Fake AI를 연결한다. 기존 DB에 접근하지 않고 OpenAI 호출도 없다. 업로드/분할/벡터 저장/제목 변경/검색/답변/삭제를 브라우저에서 실행한다. 종료 시 Spring graceful shutdown과 임시 DB 정리를 수행한다. 로그는 `target/frontend-browser.log`, 실패 자료는 `frontend/test-results-live`에 남는다. 일반 Maven 테스트에서는 이 브라우저 테스트만 명시적 옵션이 없어 제외된다.

## 실측 자료 갱신 (오프라인)

일반 frontend 빌드는 `evaluation/results`가 없어도 성공한다. 아래 export는 결과를 교체할 때만 사용한다. 원본은 수정하지 않고 화면용 allowlist JSON 두 개만 생성한다.

```powershell
.\scripts\export-frontend-data.ps1 `
  -ThresholdDir 'evaluation/results/threshold-comparison-2026-09-05T11-56-28.069288700Z-d081fd1b-c724-47d5-9eb4-cea29fd9f1eb' `
  -TopKDir 'evaluation/results/topk-comparison-2026-09-05T12-10-16.339038700Z-b616350e-8086-4ee7-9c92-e5c29c01b928' `
  -ChunkSizeDir 'evaluation/results/chunk-size-comparison-2026-09-05T16-34-49.776580300Z-4f464dfe-38e8-4e8e-a937-3eace986d88d'
```

COMPLETE·입력 해시·질문 수·조건·CSV 채점을 검증한다. Threshold/Top-K는 보고서에 기록된 원래 baseline 경로도 필요하다. 경로가 이동되었다면 자동으로 다른 baseline을 고르지 않고 실패한다. 임의의 최신 결과를 자동 선택하지 않는다.

## 제외 범위

공개 배포·로그인·사용량 제한·실험 실행 버튼은 없다. RAG Prompt Injection 수동 검증, 실제 답변 근거 충분성 검증은 별도 과제다. Fake AI 테스트 통과는 실제 공급자의 응답 품질을 보장하지 않는다.

## 검증 기록 — 2026-09-06

- `npm ci` 재설치, TypeScript 검사, production build 성공. 해당 시점 npm audit 취약점 0건.
- 프론트 단위 테스트 16개 통과, Edge 브라우저 테스트 8개 통과(1440/768/375px, 키보드, 데모 요청 0회, 오류, 인용, 텍스트 안전성, 중복 실행 방지).
- 별도 무료 실제 HTTP 브라우저 연동 1개 통과. Testcontainers/Fake AI 사용, Actuator 200/UP, graceful shutdown 확인.
- `mvn clean test`: 총 128개, 124개 통과, 4개 제외(OpenAI 2개, Tempo 실환경 1개, 별도로 통과한 브라우저 opt-in 1개).
- export 검증 테스트 3개는 위 Maven 수에 포함. 지정한 실측 보고서 세 개로 JSON 재생성 성공. 유료 API 호출 0회, 기존 DB·보고서·운영 설정 변경 없음.

이 수치는 위 날짜의 실행 기록이다. 이후 의존성 취약점이나 코드 변경은 다시 검증해야 한다.
