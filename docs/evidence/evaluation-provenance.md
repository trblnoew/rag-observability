# Golden Query 평가 증거와 재현 범위

README의 수치는 `evaluation/results` 아래에서 완료 상태(`COMPLETE`)로 생성된 로컬 실행 결과를 옮긴 검토용 스냅샷이다. 원본 실행 디렉터리는 gitignore 대상이므로, 비교에 필요한 집계 CSV만 이 디렉터리에 보존한다. API key, Embedding Vector, Prompt 전문은 포함하지 않는다.

## Baseline run

| 항목 | 기록값 |
|---|---|
| Run ID | `2026-09-05T11-49-05.080986300Z-37efaa7c-f685-45ec-8dd4-ee87aac55c07` |
| Dataset SHA-256 | `293f7724dd73da32b8f8a5d5f6998fd2dd7a91bfa34ded87cedf1afdfd324ac0` |
| Source git commit | `d31da99c3086ddb77b963693500ecb3fa965723f` |
| Source worktree | `gitDirty=true` |
| Embedding | `openai` / `text-embedding-3-small` / 1536 dimensions |
| Corpus | 3 documents / 13 chunks |
| Query configuration | 18 queries / Top-K 5 / threshold -1.0 |
| Result | Hit@5 1.0 / MRR@5 0.9166666666666666 |
| Generation calls | 0 |

`gitDirty=true`이므로 이 기록만으로 해당 commit의 깨끗한 checkout과 결과가 완전히 동일하다고 주장하지 않는다. 대신 dataset hash와 실행 산출물 hash를 함께 남겨 어떤 입력·결과를 근거로 문서를 작성했는지 식별한다.

Baseline 원본 manifest에 기록된 산출물 SHA-256은 다음과 같다.

- `hits.csv`: `248a3acd536526fedb62a8015bb7cb66b3e823173f17990554ed9d601ecece85`
- `queries.csv`: `7fdade2979ac8a527d56d6c28ba5544bea193c4bc45a60c41bfe7680cf61bbb8`
- `summary.md`: `b7cdb40033b4fee0d60e225ce7b4773f2f4c903b7fa70d2dd9f170e18dc5c8a6`

## 비교 결과 스냅샷

| 실험 | 원본 Run ID | 원본 CSV SHA-256 | 저장된 스냅샷 |
|---|---|---|---|
| Similarity threshold | `2026-09-05T11-56-28.069288700Z-d081fd1b-c724-47d5-9eb4-cea29fd9f1eb` | `e7da63c94b6239b4e53f0a5c0652a862463f4c75acc9522e3a239c8f583f8231` | [threshold-comparison.csv](threshold-comparison.csv) |
| Top-K | `2026-09-05T12-10-16.339038700Z-b616350e-8086-4ee7-9c92-e5c29c01b928` | `808f271525aa2e22cd1a008db0efb0fb693504ada6527a14e090624d05ddded5` | [topk-comparison.csv](topk-comparison.csv) |
| Chunk size | `2026-09-05T16-34-49.776580300Z-4f464dfe-38e8-4e8e-a937-3eace986d88d` | `4ef17b217b20fbf5ae3f132c107277e699869e5ad848dba50c51cb6b6d6cb4bf` | [chunk-size-comparison.csv](chunk-size-comparison.csv) |

스냅샷은 원본과 같은 row/column 값을 담는다. checkout 과정의 줄바꿈 정규화가 파일 hash를 바꿀 수 있으므로 위 SHA-256은 `evaluation/results`에 생성된 원본 CSV 식별값이다.

## 해석 한계

- Corpus는 운영 장애 문서 3개, 질문은 18개뿐이어서 외부 도메인 일반화 성능을 증명하지 않는다.
- Hit@K와 MRR@K는 기대 문서 ID 기준이다. dataset에 `relevantChunkHint`가 있어도 Chunk 근거 충분성은 채점하지 않는다.
- negative 6개 중 hard negative 3개와 unrelated 3개를 구분해 보지만, 최종 답변 factuality나 citation entailment는 평가하지 않는다.
- 재실행에는 OpenAI credential이 필요하며 model 응답의 시간에 따른 동일성까지 보장하지 않는다.

평가 생성 코드는 [EvaluationRunner](../../src/main/java/com/portfolio/ragobservability/evaluation/EvaluationRunner.java), manifest·hash 검증은 [EvaluationIntegrationTests](../../src/test/java/com/portfolio/ragobservability/evaluation/EvaluationIntegrationTests.java), 실행 진입점은 [run-evaluation.ps1](../../scripts/run-evaluation.ps1)에 있다.
