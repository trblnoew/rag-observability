# 새로운 질문으로 threshold 검증하기

## 이번에 만든 것

기존 15개 질문은 후보 threshold를 찾는 시험이었다. 새 `evaluation/retrieval-validation-set-v1.json`은 그 후보를 새 질문으로 확인하는 별도 시험지다. 기존 데이터셋과 baseline은 변경하지 않는다.

1. **시험지 고정:** 직접 질문 6개, 바꿔 말한 질문 6개, 어려운 부정 질문 3개, 완전히 무관한 질문 3개다. 정답 문서와 판단 근거를 검색 전에 저장했다. 문서 SHA-256으로 같은 원문인지 확인한다.
2. **한 번 검색:** 기존 RetrievalService가 각 질문을 Top-K=5, threshold=-1.0으로 한 번 검색한다. Query Embedding은 총 18회이며 문서 재임베딩·LLM 호출은 없다.
3. **저장 후 재채점:** 같은 결과에서 0.3/0.4/0.5 이상인 Chunk만 남긴다. 추가 API 비용 없이 합격선을 비교한다. 기준 -1.0을 포함해 4조건 × 18질문 = 72행이다.

질문과 정답을 결과에 맞춰 바꾸지 않는다. 원문 또는 정답 수정이 필요하면 새 데이터셋 버전을 만들고 독립 검증 여부를 다시 설명한다.

## 1. 무료 사전 확인

프로젝트 루트의 PowerShell에서 실행한다.

```powershell
.\scripts\run-evaluation.ps1 -DatasetPath "evaluation/retrieval-validation-set-v1.json" -ValidateOnly
```

Java 21과 Maven이 필요하다. 최초 Maven 의존성 다운로드는 가능하지만 Spring 서버·Docker·DB·API Key는 필요 없다. `.env`를 로딩하지 않는다. 질문 구성, 기존 질문과의 문자열 중복, 원문 해시를 확인하고 예상 호출 수를 출력한다. 의미상 중복과 답변 근거는 데이터셋의 `judgmentReason`을 사람이 검토한다.

`-Live` 없이 실행해도 검증과 안내만 한다. `-Live`와 `-ValidateOnly` 동시 사용은 오류다. 데이터셋 경로 생략 시 기존 15개 질문을 계속 사용한다.

## 2. 유료 평가 — 사용자가 별도로 실행

Docker PostgreSQL을 켜고 기존 샘플 3개의 Chunking/Embedding이 READY인지 확인한다. 기존 `.env`에 Embedding용 OpenAI 설정과 키가 필요하다. 키를 명령행이나 결과 파일에 넣지 않는다. 애플리케이션 서버는 켜 둘 필요 없다.

```powershell
.\scripts\run-evaluation.ps1 -DatasetPath "evaluation/retrieval-validation-set-v1.json" -Live
```

정상 실행의 **Query Embedding은 18회, Generation은 0회**다. 3개 threshold를 각각 호출하는 54회가 아니다. 워밍업·자동 재시도도 없다. 실행 전 문서 SHA-256, 상태, 벡터 모델/차원, 추가 적격 문서를 검사한다. 실패하면 이후 질문은 호출하지 않고 INCOMPLETE를 남긴다.

기존 600-token Chunk를 재사용한다. 600은 운영자가 확인하는 생성 조건이지 DB에 보존된 설정 이력이 아니다. 실제 Chunk 수는 보고서에 기록한다. 실행 중 문서 수정·삭제·추가 또는 Embedding 설정 변경을 하지 않는다.

## 3. 무료 threshold 비교

평가가 출력한 새 디렉터리를 명시한다. 최신 디렉터리를 자동 선택하지 않는다.

```powershell
.\scripts\compare-thresholds.ps1 `
  -BaselineDir "evaluation/results/<새 검증 실행 디렉터리>" `
  -Thresholds "0.3,0.4,0.5"
```

이 명령에는 Spring·DB·API Key가 필요 없다. `-Thresholds`를 생략하면 기존 후보 -1.0/0.3/0.4/0.5/0.7을 유지한다. 유한한 -1~1 값만 허용하며 중복 제거·오름차순 정렬하고 -1.0 기준 행은 항상 포함한다.

## 보고서 읽기

새 실행에는 `queries.csv`, `hits.csv`, `summary.md`, `run.json`이 생성된다. Manifest에는 고정된 질문·정답·판단 근거, 문서 해시·Chunk 수, 모델, Git 상태, 결과 파일 해시가 들어 있다. 손상되거나 불완전하면 비교를 거부한다. Manifest가 없는 기존 결과는 기존 legacy 검증으로 읽는다. 비교 시 현재 DB나 변경된 시험지를 읽어 정답을 바꾸지 않는다.

비교 디렉터리의 `comparison.csv`는 전체/유형별 점수, `query-comparison.csv`는 72개 질문별 채점, `summary.md`는 비교표와 정답 누락·빈 결과·부정 질문 반환 목록을 제공한다. 원래 실행과 비교 실행의 출처도 구분해 남긴다.

| 지표 | 해석 |
|---|---|
| Hit@5 / MRR@5 | 정답 있는 12개 중 정답 문서를 찾았는지 / 얼마나 위에 있는지 |
| DIRECT / PARAPHRASED Hit·MRR | 각 6개 유형의 성능 |
| 관련 질문 빈 결과율 | 필요한 근거까지 모두 버린 비율 |
| negativeReturnRate | 답 없는 6개 중 결과가 남은 비율 |
| hardNegativeReturnRate | 주제는 비슷하지만 답이 없는 3개 중 결과가 남은 비율 |
| unrelatedReturnRate | 완전히 무관한 3개 중 결과가 남은 비율 |

부정 질문의 Hit/RR은 공란이다. 다른 문서만 남은 정답 누락과 빈 결과는 다르다. 같은 문서의 Chunk를 중복 제거하지 않는다. 문서 수준 정답 채점이므로 실제 답변 근거가 충분한지는 별도 검토가 필요하다.

어려운 부정 질문에 결과가 남아도 LLM이 반드시 오답을 만든다는 뜻은 아니다. 이번에는 검색만 평가한다. threshold별 지연시간을 새로 주장하지 않으며, 기존·신규 데이터셋 점수를 합산하지 않는다. 낮은 점수도 결과로 보존한다.

## 완료 기준

- 구현: 자동 회귀 테스트, Fake 검색 18회 → 비교 72행, 무료 사전 검증 통과.
- 실제 검증: 사용자가 유료 평가를 실행해 COMPLETE 보고서와 세 후보의 비교를 확인.
- RAG 기본 threshold 0.5는 자동 변경하지 않는다. 결과 검토 후 별도로 결정한다.
- 다음 단계는 결과 검토 후 Top-K 비교다.
