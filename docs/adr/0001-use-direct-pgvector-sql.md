# ADR-0001: Spring AI VectorStore 대신 직접 pgvector SQL 사용

- Status: Accepted
- Date: 2026-08-28

## Context

이 프로젝트의 핵심 목표는 단순히 RAG 기능을 붙이는 것이 아니라 검색 결과의 순위, 유사도 임계값, 실행 시간과 실패 원인을 직접 측정하고 설명하는 것이다. Spring AI `VectorStore`는 Provider 간 공통 추상화와 편리한 저장·검색 API를 제공하지만, 실제 pgvector 쿼리와 거리 계산이 추상화 뒤에 숨으면 SQL 실행 계획과 검색 정책을 포트폴리오의 근거로 제시하기 어렵다.

## Decision

문서 Chunk와 Embedding은 Spring JDBC와 직접 작성한 pgvector SQL로 저장하고 검색한다.

- cosine distance 연산자 `<=>`를 명시적으로 사용한다.
- API에서 사용하는 similarity는 `1 - cosine distance`로 계산한다.
- `READY` 문서 필터, Top-K, minimum similarity를 SQL과 애플리케이션 경계에서 명확히 통제한다.
- Embedding 차원은 선택한 모델과 DB 스키마가 일치하는지 저장 전에 검증한다.
- Spring AI는 모델 호출을 위한 라이브러리로 사용하되 `VectorStore`는 사용하지 않는다.

이 ADR은 향후 검색 구현 방향만 결정하며, 현재 0단계에서는 Repository나 검색 코드를 만들지 않는다.

## Consequences

긍정적 결과:

- 실제 SQL, 거리식, 필터와 정렬 기준을 README와 실험 결과에서 재현할 수 있다.
- PostgreSQL 실행 계획과 검색 latency를 직접 관측하고 최적화할 수 있다.
- Spring AI의 VectorStore 내부 동작이나 버전 변경으로부터 검색 정책을 분리할 수 있다.

비용과 위험:

- 벡터 직렬화, JDBC 매핑, SQL 테스트와 예외 처리를 직접 구현해야 한다.
- 다른 Vector DB로 교체할 때 Repository 구현을 새로 작성해야 한다.
- Spring AI가 제공하는 VectorStore 편의 기능과 Provider 이식성을 포기한다.

## Alternatives

- Spring AI `VectorStore`: 초기 구현은 빠르지만 검색 정책과 SQL을 직접 설명하기 어렵다.
- JPA/Hibernate 기반 매핑: 일반 엔티티에는 편리하지만 pgvector 연산과 순위 쿼리를 표현하는 데 불필요한 복잡성이 생긴다.
- 별도 Vector DB: MVP 규모에서는 PostgreSQL과 운영 지점을 분리할 이점보다 복잡성 증가가 크다.

