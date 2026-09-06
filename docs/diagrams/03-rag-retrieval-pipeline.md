# RAG Retrieval Pipeline

~~~mermaid
flowchart TD
    SEARCH["POST /api/v1/search"]
    ANSWER["POST /api/v1/answers"]

    VALID_S["Normalize query<br/>validate length, Top-K, threshold"]
    VALID_A["Normalize question<br/>validate length, Top-K, threshold"]
    GEN_ON{"Generation<br/>enabled?"}
    GEN_OFF["503 GENERATION_DISABLED"]

    EMB_ON{"Embedding<br/>enabled?"}
    EMB_OFF["503 EMBEDDING_DISABLED"]
    QEMB["Create one query embedding"]
    VVALID{"One valid vector?<br/>configured dimensions"}
    EMB_ERR["502 provider or<br/>validation error"]

    ELIGIBLE["Eligible chunks only<br/>document READY · embedding READY<br/>same provider, model, dimensions"]
    COSINE["Exact pgvector cosine search<br/>distance = embedding &lt;=&gt; query"]
    FILTER["similarity threshold<br/>deterministic ordering<br/>LIMIT Top-K"]

    SEARCH_OUT["Search response<br/>ranked chunks and metadata"]
    EVIDENCE{"Any evidence?"}
    NO_EVIDENCE["Fixed no-evidence answer<br/>generationCalled=false"]
    CONTEXT["Numbered context<br/>S1, S2, ..."]
    PROMPT["Prompt: use context only<br/>and cite source IDs"]
    CHAT["Chat generation"]
    CITE{"Citation syntax<br/>and ID range valid?"}
    CITE_ERR["502 GENERATION_INVALID_RESPONSE"]
    ANSWER_OUT["Answer response<br/>answer, sources, metadata"]

    SEARCH --> VALID_S --> EMB_ON
    ANSWER --> VALID_A --> GEN_ON
    GEN_ON -->|no| GEN_OFF
    GEN_ON -->|yes| EMB_ON

    EMB_ON -->|no| EMB_OFF
    EMB_ON -->|yes| QEMB --> VVALID
    QEMB -. provider error .-> EMB_ERR
    VVALID -->|no| EMB_ERR
    VVALID -->|yes| ELIGIBLE --> COSINE --> FILTER

    FILTER -->|search endpoint| SEARCH_OUT
    FILTER -->|answer endpoint| EVIDENCE
    EVIDENCE -->|no| NO_EVIDENCE
    EVIDENCE -->|yes| CONTEXT --> PROMPT --> CHAT --> CITE
    CITE -->|no| CITE_ERR
    CITE -->|yes| ANSWER_OUT
~~~

## 이 그림에서 면접관이 봐야 할 핵심 포인트

- Query와 저장 Vector의 provider·model·dimension을 맞춘 뒤 pgvector cosine distance를 exact scan으로 계산한다.
- Threshold, 안정적인 동점 정렬, Top-K를 SQL에서 적용하며 현재 ANN·hybrid search·reranking·deduplication은 없다.
- 근거가 없으면 생성 호출을 생략하고, 근거가 있으면 번호 Context와 인용 형식 검증을 거쳐 답변을 반환한다.

## 코드 근거

[RetrievalService](../../src/main/java/com/portfolio/ragobservability/retrieval/RetrievalService.java) · [RetrievalRepository](../../src/main/java/com/portfolio/ragobservability/retrieval/RetrievalRepository.java) · [RagService](../../src/main/java/com/portfolio/ragobservability/rag/RagService.java) · [ContextBuilder](../../src/main/java/com/portfolio/ragobservability/rag/ContextBuilder.java) · [CitationValidator](../../src/main/java/com/portfolio/ragobservability/rag/CitationValidator.java)
