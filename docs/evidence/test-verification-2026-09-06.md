# 테스트 검증 기록 — 2026-09-06

이 문서는 현재 작업 트리에서 직접 실행한 회귀 테스트 결과를 기록한다. Source checkout은 `a257f9fa94875041ac83698102ed1deffd01c520`이며 작업 트리에 문서·테스트 변경이 있어 dirty 상태였다. CI 실행 이력이나 clean commit 결과로 해석해서는 안 된다.

## Backend

~~~powershell
.\mvnw.cmd clean test
~~~

| 항목 | 결과 |
|---|---:|
| 전체 | 128 |
| Passed | 124 |
| Skipped | 4 |
| Failure / Error | 0 / 0 |

- Java 21.0.12.1, Docker Desktop server 29.7.2에서 실행했다.
- Testcontainers가 `pgvector/pgvector:0.8.6-pg17-trixie`를 시작했고 Flyway V1~V3을 PostgreSQL 17.11에 적용했다.
- Skip 4개는 opt-in 외부 통합 테스트다: 실제 OpenAI 2개, 실제 Tempo 1개, browser integration 1개.
- 실제 Tempo 테스트는 아래 명령으로 별도 활성화했고 7/7 통과했다.

~~~powershell
.\mvnw.cmd "-Dtest=TracingHttpTests" "-Dobservability.live=true" test
~~~

상세 Trace 결과는 [OpenTelemetry live path 검증](observability-verification-2026-09-06.md)에 있다.

## Frontend

~~~powershell
Set-Location frontend
npm test -- --run
npm run build
~~~

- Vitest: 1 test file, 16 tests passed.
- TypeScript project build와 Vite 7.3.6 production build가 통과했다.
- Build output: 32 modules transformed. 생성된 `dist` 용량은 환경과 minifier 버전에 따라 달라질 수 있어 성과 지표로 사용하지 않는다.

## 검증 한계

- GitHub Actions 등 CI pipeline은 아직 없다.
- OpenAI 실제 호출은 비용·credential이 필요한 opt-in 테스트여서 이 회귀 실행에서는 skip했다.
- 부하, 동시성, 장애 주입, 장시간 soak test 결과는 없다.
