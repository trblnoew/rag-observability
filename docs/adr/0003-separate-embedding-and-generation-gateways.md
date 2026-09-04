# ADR-0003: EmbeddingGateway와 GenerationGateway 분리

- Status: Accepted
- Date: 2026-08-28

## Context

Embedding과 답변 생성은 모두 외부 AI Provider를 호출하지만 입력·출력, 비용, timeout, 재시도와 실패 영향이 다르다. 두 책임을 하나의 AI 서비스에 묶으면 문서 수집 실패와 답변 생성 실패를 구분하기 어렵고, 검색만 테스트하거나 Fake 구현을 사용하는 것도 어려워진다.

## Decision

향후 AI 연동 경계를 `EmbeddingGateway`와 `GenerationGateway`로 분리한다.

- `EmbeddingGateway`는 문서 Chunk와 질의를 벡터로 변환하고 모델·차원 정보를 제공하는 책임만 가진다.
- `GenerationGateway`는 질문과 검색된 Context를 받아 근거가 포함된 답변과 사용량 정보를 반환하는 책임만 가진다.
- 두 Gateway는 timeout, 예외 매핑, 관측 속성과 Fake 테스트 대역을 독립적으로 가진다.
- Provider 선택은 Adapter 내부로 제한하며 도메인·검색 코드는 Provider SDK 타입에 의존하지 않는다.
- Embedding 모델 변경은 저장된 전체 벡터의 재생성이 필요하고, 생성 모델 변경은 기존 벡터를 무효화하지 않는다는 정책을 분리한다.

이 ADR은 경계와 책임만 결정한다. 현재 0단계에서는 Java 인터페이스, Adapter 또는 외부 API 호출을 구현하지 않는다.

## Consequences

긍정적 결과:

- 검색 파이프라인과 답변 생성 파이프라인을 독립적으로 테스트할 수 있다.
- 장애 유형, latency와 비용을 단계별로 구분할 수 있다.
- Embedding Provider와 Generation Provider를 독립적으로 교체할 수 있다.

비용과 위험:

- 작은 MVP에 인터페이스와 Adapter 계층이 추가된다.
- Provider별 응답 메타데이터를 공통 모델로 변환해야 한다.
- 과도하게 일반화하지 않도록 최초 구현은 하나의 Provider로 제한해야 한다.

## Alternatives

- 단일 `AiGateway`: 코드 수는 줄지만 책임과 장애 경계가 결합된다.
- Spring AI 타입 직접 사용: 초기 연결은 빠르지만 도메인 코드가 프레임워크와 Provider 세부사항에 결합된다.
- 여러 Provider 동시 구현: 이식성을 즉시 검증할 수 있지만 MVP 범위를 벗어난다.

