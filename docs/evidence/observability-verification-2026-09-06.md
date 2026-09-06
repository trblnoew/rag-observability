# OpenTelemetry live path 검증 — 2026-09-06

이 기록은 Fake Embedding/Generation Gateway로 RAG 분기를 고정한 뒤, 애플리케이션이 만든 Span을 실제 `OpenTelemetry Collector → Tempo` 경로로 전송해 조회한 결과다. OpenAI 호출 품질을 검증한 실행은 아니다.

## 실행 환경과 명령

- Java: 21.0.12.1
- Test database: Testcontainers `pgvector/pgvector:0.8.6-pg17-trixie` (PostgreSQL 17.11)
- Collector: `otel/opentelemetry-collector-contrib:0.159.0`
- Tempo: `grafana/tempo:2.10.8`
- Source checkout: `a257f9fa94875041ac83698102ed1deffd01c520`, dirty working tree

~~~powershell
.\mvnw.cmd "-Dtest=TracingHttpTests" "-Dobservability.live=true" test
~~~

결과: 7 tests, 7 passed, 0 skipped, 0 failures/errors.

## Tempo 조회 결과

| 분기 | 응답 `X-Trace-Id` | 전체 Span | unique Span ID | Custom Span |
|---|---|---:|---:|---:|
| Evidence 있음 | `868deea27bbe0ec34e6a73583f11847e` | 9 | 9 | 7 |
| No evidence | `65dbdbb2aba9fd912e095799d8398912` | 6 | 6 | 4 |

Evidence가 있는 Trace의 Custom Span은 `rag.answer`, `rag.retrieval`, `rag.query_embedding`, `rag.vector_search`, `rag.context_build`, `rag.generation`, `rag.citation_validation`이다. No-evidence Trace에는 앞의 네 개만 존재하고 generation 관련 Span은 생성되지 않았다.

정상 답변의 부모 관계는 HTTP server span → `rag.answer` → (`rag.retrieval`, `rag.context_build`, `rag.generation`, `rag.citation_validation`)이며, `rag.query_embedding`과 `rag.vector_search`는 `rag.retrieval`의 자식이다. 이 관계는 in-memory finished Span의 `parentSpanId` assertion으로 검증한다.

전체 Span 수에는 Spring Boot가 만든 HTTP server/client Span이 포함된다. `unique Span ID`가 전체 Span 수와 같음을 별도 Tempo API 조회로 확인해 같은 Span의 중복 전송이 없음을 확인했다. Trace ID는 [Tempo 설정](../../infra/tempo/tempo.yaml)의 24시간 retention 이후 조회되지 않을 수 있다.

## 검증 코드와 범위

[TracingHttpTests](../../src/test/java/com/portfolio/ragobservability/observability/TracingHttpTests.java)는 다음을 함께 확인한다.

- 응답 `X-Trace-Id`와 조회 Trace ID가 같고, 정상/근거 없음 분기에 필요한 Span 이름이 존재한다.
- 질문·문서·provider 오류에 심은 `SECRET_` 문자열이 finished Span과 Tempo 응답에 포함되지 않는다.
- 기본 실행에서는 도달 불가능한 OTLP endpoint를 사용해 Collector 장애가 API 응답을 실패시키지 않는지 확인한다.
- Live 실행에서는 test configuration이 in-memory exporter와 명시적 OTLP exporter를 각각 한 번 등록한다.

이 검증은 로컬 단일 실행 증거이며 지속적인 CI나 장기 보관 모니터링을 뜻하지 않는다.
