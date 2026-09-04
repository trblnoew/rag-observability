# ADR-0004: Spring Boot 3.5.16 / Spring AI 1.1.8 버전 baseline 고정

- Status: Accepted
- Date: 2026-08-28

## Context

프로젝트 시작 시점에는 Spring Boot 4와 Spring AI 2가 제공되지만, 이 프로젝트의 목적은 최신 major migration 자체가 아니라 RAG 검색 품질과 관측성을 검증하는 것이다. Spring Boot 3.5와 Spring AI 1.1 계열은 함께 유지되는 안정 조합이며, Java 21 LTS와 기존 Spring 경험을 활용할 수 있다.

## Decision

0단계 baseline을 다음과 같이 고정한다.

- Java 21 LTS
- Spring Boot 3.5.16
- Spring AI BOM 1.1.8
- Maven Wrapper 3.9.16

`pom.xml`의 Spring Boot parent와 Spring AI BOM을 버전의 단일 기준으로 사용한다. 보안 수정 또는 치명적 결함이 아니라면 MVP 완료 전 major version을 변경하지 않는다. Spring Boot 4 / Spring AI 2 전환은 MVP 결과가 재현된 뒤 별도 ADR과 전체 회귀 테스트를 통해 결정한다.

## Consequences

긍정적 결과:

- 이미 검증된 Java 21과 Spring 6.2 기반에서 기능 구현에 집중할 수 있다.
- Spring Boot와 Spring AI 간 의존성 호환 범위를 명확히 고정한다.
- 실험 도중 프레임워크 major 변경으로 결과가 흔들리는 것을 방지한다.

비용과 위험:

- Spring Boot 4와 Spring AI 2의 신규 API와 개선 기능을 즉시 사용하지 않는다.
- 장기 유지 시 Jakarta/Framework 변경과 Spring AI breaking change를 포함한 별도 migration 비용이 발생한다.
- baseline 버전에도 보안 업데이트가 필요하므로 정기적인 의존성 점검은 계속해야 한다.

## Alternatives

- Spring Boot 4 + Spring AI 2: 최신 기능을 사용할 수 있지만 학습·migration 범위가 RAG 검증과 섞인다.
- Spring Boot/Spring AI 최신 버전 자동 추종: 보안 수정 반영은 빠르지만 재현성과 빌드 안정성이 떨어진다.
- Spring AI 미사용: 외부 API를 완전히 직접 제어할 수 있지만 모델 연동을 위한 공통 기능을 다시 작성해야 한다.

