# 배포 실패와 Rollback 대응 Runbook

## 문서 목적

이 문서는 새 버전 배포 중 readiness 실패, 오류율 증가 또는 Flyway migration 문제를 발견했을 때 중단과 rollback을 결정하는 절차다. 배포 성공은 새 컨테이너 프로세스가 시작됐다는 뜻이 아니라 실제 요청을 받을 준비가 됐고 핵심 사용자 흐름이 정상이라는 뜻이다. 대응자는 liveness와 readiness를 구분하고, 애플리케이션 버전과 DB 스키마의 호환성을 확인한 뒤 복구 방법을 선택해야 한다.

## 배포 중단 기준

canary 단계에서 새 버전의 오류율, p95 latency, readiness 성공률을 이전 버전과 비교한다. readiness probe가 반복 실패하거나 핵심 API의 5xx 비율이 사전 정의한 한도를 넘으면 추가 트래픽 전환을 즉시 멈춘다. 배포 도구의 진행률만 보지 말고 실제 load balancer가 새 pod에 어느 정도 트래픽을 전달했는지 확인한다. 오류가 새 버전에만 집중되면 canary weight를 0으로 낮추는 것이 가장 빠른 격리 수단이다.

liveness probe 실패는 프로세스를 재시작시키지만 readiness probe 실패는 해당 인스턴스를 서비스 대상에서 제외한다. 시작 시간이 긴 애플리케이션에 liveness 임계값을 너무 짧게 두면 정상 초기화 중인 pod가 반복 재시작될 수 있다. 반대로 readiness가 DB 연결, 필수 migration 또는 캐시 준비를 충분히 반영하지 않으면 준비되지 않은 인스턴스가 사용자 요청을 받는다. probe 실패 이유와 애플리케이션 시작 로그를 함께 확인해 단순 시간 부족인지 실제 의존성 실패인지 분류한다.

## Flyway와 DB 호환성

Flyway validation 실패가 보이면 적용된 migration checksum과 애플리케이션에 포함된 migration 파일을 비교한다. 이미 운영 DB에 적용된 migration 파일을 수정해서 checksum을 맞추려 하지 않는다. 필요한 변경은 새로운 버전의 migration으로 추가하고, 누가 언제 어떤 환경에 적용했는지 기록한다. migration이 일부 환경에서만 적용됐다면 배포를 계속하기 전에 환경별 `flyway_schema_history` 상태를 확인한다.

DB 스키마 변경이 완료된 뒤 애플리케이션만 이전 버전으로 rollback할 수 있는지 반드시 판단한다. 컬럼 삭제나 타입 변경처럼 이전 코드와 호환되지 않는 migration은 단순 애플리케이션 rollback을 위험하게 만든다. 안전한 배포는 먼저 nullable 컬럼이나 새 테이블을 추가하고, 구버전과 신버전이 함께 동작하는 기간을 둔 다음, 데이터 전환이 끝난 뒤 오래된 구조를 제거하는 expand-and-contract 방식을 따른다. migration 자체의 되돌리기가 데이터 손실을 만들 수 있다면 즉시 reverse SQL을 실행하지 않고 forward fix를 우선 검토한다.

## 설정과 Secret 확인

새 버전만 시작하지 못한다면 환경변수 이름, Secret mount, profile과 외부 서비스 endpoint 차이를 확인한다. Secret 값 자체를 로그나 채팅에 복사하지 말고 존재 여부, 버전과 주입 경로만 검증한다. 잘못된 API key나 DB password가 원인이어도 값을 출력해서 비교하지 않는다. 설정 변경과 코드 변경이 동시에 배포됐다면 각각의 변경 시각을 분리해 어떤 요소가 장애를 만들었는지 추적한다.

이미지 tag가 재사용됐거나 digest가 환경마다 다르면 같은 버전 이름으로 서로 다른 코드가 실행될 수 있다. 실행 중인 pod의 image digest, Git commit과 build timestamp를 확인한다. ConfigMap이나 Secret 갱신 뒤 기존 pod가 자동으로 새 값을 읽는다고 가정하지 않는다. 설정 반영 방식에 따라 명시적 rollout이 필요하며, 이때도 canary 단계부터 다시 검증한다.

## Rollback 실행

rollback 전에 현재 배포 버전, 대상 이전 버전, DB migration 상태와 데이터 호환성을 한 줄로 정리한다. 이전 버전 이미지가 registry에 존재하고 서명이 유효한지 확인한다. canary 트래픽을 먼저 이전 버전으로 돌리고 핵심 API와 readiness가 회복되는지 본다. 확인되지 않은 상태에서 전체 replica를 동시에 교체하면 복구 실패 시 정상 인스턴스까지 잃을 수 있다.

rollback이 시작되면 배포 자동화가 다시 신버전을 적용하지 않도록 일시 중지 상태를 확인한다. 새 버전이 만든 비동기 작업이나 메시지가 이전 버전에서 처리 가능한지도 검토한다. 스키마가 backward compatible하고 데이터 형식이 유지될 때만 애플리케이션 rollback을 계속한다. 호환되지 않는 데이터가 이미 저장됐다면 해당 경로를 차단하고 forward fix 또는 변환 작업을 선택한다.

## 검증과 종료 기준

이전 버전으로 전환한 뒤 readiness probe 성공만으로 종료하지 않는다. 실제 사용자와 같은 경로의 smoke test를 실행하고 오류율, p95 latency와 처리량을 최소 15분 관찰한다. Flyway 상태가 예상 버전인지, 신규 migration이 남아 있어도 이전 코드가 안전하게 동작하는지 확인한다. canary와 안정 버전 사이에 설정, Secret과 DB endpoint 차이가 없는지도 다시 확인한다.

복구가 확인되면 실패한 버전의 자동 재배포를 막고 incident timeline을 작성한다. timeline에는 최초 canary 시작, readiness 실패, 오류율 임계값 초과, 트래픽 차단, rollback 시작과 회복 시각을 포함한다. 사용자 영향과 처리되지 못한 작업이 있다면 재처리 조건과 멱등성을 확인한다. 임시로 완화한 probe threshold나 배포 정책은 원인 수정 뒤 원래 기준으로 복구한다.

## 재발 방지

배포 파이프라인에는 canary health gate, 핵심 API smoke test와 Flyway validation을 명시적으로 둔다. rollback 연습에서는 애플리케이션 버전뿐 아니라 migration 이후 구버전 호환성도 확인한다. 위험한 스키마 변경은 expand-and-contract 단계와 제거 시점을 ADR 또는 배포 문서에 남긴다. 최종적으로 안전한 rollback은 버튼 하나가 아니라 readiness, canary 지표, 설정 일관성과 DB backward compatibility가 함께 보장될 때 가능한 절차다.
