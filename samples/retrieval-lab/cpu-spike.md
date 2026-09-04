# 컨테이너 CPU 사용률 급증 대응 Runbook

## 문서 목적

이 문서는 컨테이너 기반 Spring Boot 서비스의 CPU 사용률이 급증하고 처리량이 떨어질 때 원인을 분류하는 절차다. 높은 CPU는 항상 애플리케이션 코드가 계산을 많이 했다는 뜻은 아니다. 컨테이너가 CPU limit에 막혀 throttling 되는 경우, JVM의 GC가 반복되는 경우, 특정 스레드가 무한 반복하는 경우와 정상적인 트래픽 증가를 구분해야 한다. 대응자는 평균 CPU 그래프 하나만 보지 말고 사용량, 제한량, throttled time, 요청량과 지연 시간을 같은 구간에서 비교한다.

## 현상 분류

먼저 장애가 단일 pod에만 나타나는지 전체 replica에 동시에 나타나는지 확인한다. 단일 pod만 비정상이라면 특정 요청, 로컬 캐시 상태, 비정상 스레드 또는 노드 자원 경합 가능성이 높다. 모든 pod가 비슷하게 상승했다면 트래픽 변화, 공통 배포, 외부 응답 변화 또는 전체 용량 부족을 의심한다. 배포 버전과 설정 hash가 pod마다 같은지도 확인해 서로 다른 바이너리가 섞인 상황을 배제한다.

컨테이너 CPU usage가 limit에 가까운지와 CFS throttling 지표가 증가하는지를 확인한다. CPU limit이 1 core인데 애플리케이션이 2 core 분량의 작업을 요구하면 사용률은 제한 안에 보이더라도 실행 시간이 반복적으로 잘릴 수 있다. 이때 요청 latency는 늘고 처리량은 줄며 throttled periods가 증가한다. 단순히 JVM 프로세스의 CPU 백분율만 보면 이 차이를 놓칠 수 있으므로 Kubernetes request와 limit, 노드의 실제 여유까지 함께 본다.

## 트래픽과 런타임 확인

요청 수가 CPU와 함께 증가했다면 엔드포인트별 RPS와 처리 비용을 비교한다. 전체 요청 수는 비슷해도 대용량 payload나 비싼 검색 요청 비율이 늘면 CPU가 상승할 수 있다. 오류를 만난 클라이언트가 빠르게 재시도하는지도 확인한다. 재시도 폭풍은 동일 작업을 여러 번 실행해 CPU를 소모하고 downstream에 추가 부하를 전달하므로 rate limit과 backoff 상태를 점검한다.

JVM에서는 heap 사용량, allocation rate, young GC 횟수, full GC 시간과 pause를 확인한다. CPU 상승 구간에 allocation rate와 GC CPU가 함께 증가하면 객체 생성이 지나치게 많은 코드나 heap 압박 가능성이 있다. full GC가 반복되는데 회수 후 사용량이 거의 줄지 않으면 메모리 보유 경로를 조사해야 한다. 반대로 GC 지표가 안정적이면 hot method, 직렬화, 압축, 암호화 또는 busy loop 같은 순수 계산 경로를 우선 조사한다.

## Thread dump와 프로파일링

증상이 지속되는 동안 수 초 간격으로 thread dump를 최소 세 번 수집한다. 한 번의 dump는 우연히 실행 중인 스레드만 보여줄 수 있지만 여러 dump에서 같은 stack이 RUNNABLE 상태로 반복되면 CPU hot path 후보가 된다. 동일 스레드 이름과 native thread id를 OS 또는 컨테이너의 CPU 사용 상위 스레드와 연결한다. 민감한 요청 데이터가 stack이나 로컬 변수에 포함될 수 있으므로 dump 파일은 접근이 제한된 위치에 보관한다.

여러 dump에서 JSON 직렬화, 정규식, 압축, 암호화 또는 특정 반복문이 계속 상단에 보이는지 확인한다. lock을 기다리는 BLOCKED 스레드가 많다면 CPU 문제처럼 보이지만 실제 병목은 동기화 경합일 수 있다. RUNNABLE 스레드 수가 core 수보다 지나치게 많으면 context switching 비용도 커질 수 있다. 짧은 프로파일링이 허용되면 Java Flight Recorder나 async-profiler로 CPU sample을 수집하되 운영 부하와 수집 시간을 제한한다.

## 안전한 완화

트래픽 증가가 원인이고 새 replica가 정상적으로 준비될 수 있다면 수평 확장을 검토한다. 그러나 CPU limit이 너무 낮은 상태에서 replica만 늘리면 각 pod가 같은 throttling을 겪을 수 있다. 반대로 노드 전체가 포화라면 pod 확장이 스케줄링되더라도 실제 CPU를 얻지 못한다. request와 limit 변경은 배포 정책과 클러스터 용량을 확인한 뒤 작은 단계로 수행한다.

특정 기능이나 엔드포인트가 원인이라면 기능 플래그, 동시성 제한 또는 임시 rate limit으로 부하를 격리한다. 문제가 시작된 배포가 명확하고 이전 버전이 데이터 스키마와 호환된다면 rollback을 선택한다. 프로세스 재시작은 hot loop를 잠시 제거할 수 있지만 원인을 숨길 수 있으므로, 재시작 전에 가능한 범위에서 thread dump와 주요 지표를 확보한다. 무제한 자동 재시작은 증거를 잃고 동일 장애를 반복할 위험이 있다.

## 복구 확인과 사후 조치

복구는 CPU 사용률만 낮아진 것으로 판단하지 않는다. throttled time이 정상 범위로 돌아오고 요청 처리량, p95 latency, 오류율이 함께 회복돼야 한다. GC pause와 allocation rate, runnable thread 수가 안정화됐는지 확인한다. replica를 늘렸다면 scale-in 뒤에도 동일 부하를 감당할 수 있는지 또는 새 기준 용량을 유지할지 결정한다.

사후 분석에는 CPU usage와 limit, throttling 비율, pod와 노드 범위, 요청량 변화, GC 지표와 반복된 thread stack을 포함한다. 코드 수정이 필요하면 동일 입력을 재현하는 부하 테스트와 profiler 결과를 함께 남긴다. 경보는 CPU 평균값 하나보다 throttling, latency와 오류율을 결합해 구성한다. 최종적으로 CPU 급증은 애플리케이션 계산, JVM 메모리 관리, 컨테이너 제한과 트래픽 형태를 분리해 진단해야 재발 방지책을 정확히 선택할 수 있다.
