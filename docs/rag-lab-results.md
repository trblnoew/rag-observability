# RAG 답변 생성 수동 학습·검증 기록지

이 실습은 Retrieval 결과가 `[S1]`, `[S2]` Context로 조립되고, LLM 답변의 인용과 실제 API `sources`가 일치하는지 확인한다. 먼저 [Retrieval 실습](retrieval-lab-results.md)을 완료하고 [IntelliJ RAG 요청](../http/rag-lab.http)을 위에서 아래로 실행한다.

## 사전 확인

- [ ] 세 문서 모두 `status=READY`, `embeddingStatus=READY`
- [ ] Retrieval 직접 질문과 CPU 질문의 Top-1이 기대 문서와 일치
- [ ] `.env`에서 Embedding과 Chat provider를 `openai`로 활성화
- [ ] 새 API Key는 `.env`에만 존재
- [ ] 애플리케이션 health가 `200/UP`

## 결과 기록

| 질문 유형 | threshold | generationCalled | 첫 Source | Source 수 | input/output tokens | 결과 요약 |
|---|---:|---|---|---:|---|---|
| 직접 DB 질문 | 0.5 |  |  |  |  |  |
| 바꿔 말한 DB 질문 | 0.5 |  |  |  |  |  |
| CPU 질문 | 0.5 |  |  |  |  |  |
| 관련 없음 | 0.5 |  |  |  |  |  |
| Prompt Injection | 0.5 |  |  |  |  |  |
| 관련 없음 | 0.7 |  |  |  |  |  |

## 인용 검증

각 답변에 대해 다음을 확인한다.

- [ ] 답변에 최소 하나의 `[Sn]` 인용이 있다.
- [ ] 본문에서 사용한 모든 `[Sn]`이 `sources[].sourceId`에 존재한다.
- [ ] Source의 `documentId`, 제목, Chunk index가 실제 Retrieval 결과와 일치한다.
- [ ] 응답에 query vector나 1536개 숫자 배열이 없다.
- [ ] Source excerpt는 최대 500자이고 LLM에는 Chunk 전문이 전달된다.

## 근거 부족 검증

`연차 신청은 누구에게 승인받아야 하나요?` 질문을 threshold `0.5`와 `0.7`로 비교한다.

| threshold | sourceCount | answer | generationCalled | token 사용량 |
|---:|---:|---|---|---|
| 0.5 |  |  |  |  |
| 0.7 |  |  |  |  |

초기 `0.5`에서도 관련 없는 Chunk가 통과한다면 구현 실패로 단정하지 않는다. Retrieval 실습의 similarity 분포를 근거로 `RAG_ANSWER_MINIMUM_SIMILARITY`를 조정하고, 최종 값은 Golden Query 평가 단계에서 확정한다.

## 완료 기준

- [ ] 관련 질문은 기대 문서를 Source로 사용한다.
- [ ] 인용 ID와 실제 Source가 모두 일치한다.
- [ ] 존재하지 않는 Source ID가 외부 응답으로 나가지 않는다.
- [ ] 근거 없는 질문은 적절한 threshold에서 고정 문구로 거절된다.
- [ ] 근거 부족 응답은 `generationCalled=false`, token 사용량 `null`이다.
- [ ] Prompt Injection 문구가 System 규칙을 바꾸지 못한다.
- [ ] API Key, 질문, Context 전문과 Prompt 전문이 로그에 없다.

실습 종료 후 애플리케이션을 `Ctrl+C`로 graceful shutdown한다. 실제 호출이 더 필요하지 않으면 `.env`의 API Key를 제거하고 두 AI provider를 `none`으로 되돌린다. DB 문서와 vector는 Golden Query 평가 단계에서 재사용할 수 있다.
