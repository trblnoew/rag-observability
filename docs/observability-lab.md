# Custom Span 실습

Trace는 요청 한 건, Span은 그 안의 작업 구간입니다. 부모 시간에 자식 시간이 포함되므로 모두 더하지 않습니다.

검색은 `rag.retrieval → rag.query_embedding / rag.vector_search`로 표시됩니다. 답변 요청은 `rag.answer` 아래에 검색, `rag.context_build`, `rag.generation`, `rag.citation_validation`이 순서대로 나타납니다.

## 직접 확인

1. Docker 인프라를 실행하고 기존 READY 문서를 유지합니다. `scripts/run-app.ps1`로 서버를 시작합니다.
2. 실제 질문 실습은 유효한 OpenAI Embedding 설정이 필요하며, 답변은 Generation 설정도 필요합니다. API Key는 `.env`에만 보관합니다.
3. `http/observability-lab.http`의 요청을 한 건씩 실행합니다. 실제 OpenAI 호출에는 비용이 발생합니다.
4. 응답의 `X-Trace-Id`를 복사합니다. `http://localhost:3000`의 Grafana에서 Explore → Tempo → Trace ID 조회에 입력합니다.
5. 비동기 전송 때문에 잠시 기다려야 할 수 있습니다. 서비스 이름은 `rag-observability`입니다.
6. 검색 시간 중 Query Embedding과 DB 검색 시간이 얼마나 차지하는지 봅니다. 느린 첫 요청의 원인은 실제 구간별 시간을 보고 판단합니다.
7. 근거 부족이면 `rag.answer`의 `rag.outcome=no_evidence`, `rag.generation.called=false`이며 생성 구간은 없습니다.

오류는 `rag.error.code`와 error 상태로 확인합니다. 원래 provider 오류 메시지·질문·원문·벡터는 기록하지 않습니다. 입력·출력 token 정보는 공급자가 알려 준 경우에만 기록합니다.

## 무료 자동 검증

```powershell
.\mvnw.cmd test '-Dtest=RagTracingTests,TracingHttpTests'
# Docker Collector·Tempo가 실행 중일 때 실제 전송 검증 (Fake 사용, OpenAI 호출 없음)
.\mvnw.cmd test '-Dtest=TracingHttpTests' '-Dobservability.live=true'
```

테스트 전용 Fake Gateway와 별도 PostgreSQL Testcontainer를 사용합니다. 일반 테스트의 exporter는 접근 불가능한 주소를 사용해 전송 실패에도 API 응답이 유지되는지 확인합니다. 이때 exporter 연결 오류 로그는 의도된 결과입니다. live 검증은 실제 Collector로 내보낸 두 Trace를 Tempo API에서 다시 조회합니다.

tracing을 끄면 Custom Span과 `X-Trace-Id`가 생기지 않습니다. evaluation 프로필은 계속 tracing을 끕니다. 샘플링이 1.0보다 낮으면 Trace ID가 있어도 Tempo에 저장되지 않을 수 있습니다. Collector 장애 중 유실된 Trace가 재시작 후 반드시 복구되는 것은 아닙니다.

Tempo 보존 기간은 현재 24시간입니다. 포트폴리오 증거는 Trace ID·실행 조건·화면 캡처로 별도 보관하세요.

| 시나리오 | Trace ID | 확인 사항 |
|---|---|---|
| 정상 답변 | `5f52ee9246ccd0dc3f7f1a70dc6ea8dc` | 2026-09-05 Fake 검증: 7개 Custom Span, 올바른 부모 관계 |
| 근거 부족 | `aa85851b9a81c2c13903be7c64f9cde1` | 2026-09-05 Fake 검증: 생성 생략, no_evidence |
| 오류 | | 실패 구간·안전한 오류 코드 |

서버 종료는 Ctrl+C로 수행하여 graceful shutdown 로그를 확인합니다. 현재 RAG threshold 0.5는 유지하며 검색 품질 튜닝은 후속 평가에서 진행합니다.
