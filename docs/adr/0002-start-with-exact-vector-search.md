# ADR-0002: Exact Vector Search부터 시작

- Status: Accepted
- Date: 2026-08-28

## Context

pgvector는 exact search와 HNSW/IVFFlat 기반 approximate search를 모두 지원한다. MVP의 corpus와 Golden Query는 소규모이며, 첫 실험의 목적은 Chunk 크기와 Top-K가 검색 품질에 미치는 영향을 정확하게 측정하는 것이다. 처음부터 근사 인덱스를 사용하면 검색 누락이 데이터·Embedding·Chunking 문제인지 인덱스 파라미터 문제인지 분리하기 어렵다.

## Decision

초기 검색은 vector index 없이 cosine exact search로 구현한다.

- 모든 후보 벡터를 기준으로 결정적인 검색 결과를 얻는다.
- Chunk Size × Top-K 실험의 품질 baseline은 exact search 결과로 고정한다.
- 초기 단계에서는 HNSW와 IVFFlat 인덱스 및 관련 튜닝 파라미터를 도입하지 않는다.
- corpus 증가로 p95 latency 목표를 만족하지 못할 때만 별도 ADR과 동일 Golden Set 비교를 거쳐 approximate search를 검토한다.

이 ADR은 검색 방식만 결정하며, 현재 0단계에서는 Vector Search나 인덱스를 추가하지 않는다.

## Consequences

긍정적 결과:

- 동일 데이터와 질의에서 재현 가능한 순위와 품질 기준을 얻는다.
- 검색 품질 문제와 성능 문제를 분리할 수 있다.
- 인덱스 생성·튜닝 없이 SQL과 평가 파이프라인을 먼저 검증할 수 있다.

비용과 위험:

- 검색 비용이 후보 Chunk 수에 비례해 증가한다.
- corpus가 커지면 latency와 CPU 사용량이 빠르게 증가할 수 있다.
- 대규모 운영 환경의 검색 구조를 현재 결과만으로 대표할 수 없다.

## Alternatives

- HNSW: 빠른 검색과 높은 recall을 기대할 수 있지만 메모리·빌드 비용과 파라미터 튜닝이 필요하다.
- IVFFlat: 인덱스 크기와 속도 이점이 있지만 학습 데이터와 `lists`/`probes` 튜닝이 필요하다.
- 외부 Vector DB: 확장 기능은 많지만 MVP에 별도 운영 복잡성을 추가한다.

