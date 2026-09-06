# Document Ingestion Pipeline

~~~mermaid
flowchart TD
    UP["POST /api/v1/documents<br/>TXT or Markdown · max 1 MB"]
    CHECK["Filename, extension, size,<br/>strict UTF-8 validation"]
    HASH["SHA-256 of original bytes<br/>BOM removed from stored text"]
    UNIQUE{"content_sha256<br/>already exists?"}
    DUP["409 DOCUMENT_DUPLICATE"]
    STORE[("document INSERT<br/>status=PENDING<br/>embedding_status=NOT_STARTED")]

    PROCESS["POST /documents/{id}/process"]
    CLAIM_C["Claim document<br/>status=PROCESSING"]
    SPLIT["TokenTextSplitter<br/>target 600 tokens"]
    VALID_C{"Valid chunks?<br/>1..500, nonblank"}
    COMPLETE_C["REQUIRES_NEW complete<br/>replace all chunks"]
    CHUNK_READY[("document_chunk rows<br/>document status=READY<br/>embedding_status=PENDING")]
    FAIL_C["REQUIRES_NEW fail<br/>delete partial chunks<br/>status=FAILED"]

    EMBED["POST /documents/{id}/embeddings"]
    CLAIM_E["Claim embedding work<br/>clear previous vectors<br/>embedding_status=PROCESSING"]
    BATCH["Chunk content batches<br/>default size 32"]
    OPENAI["Embedding provider"]
    VALID_E{"Count and 1536 dimensions<br/>finite and not all-zero"}
    COMPLETE_E["REQUIRES_NEW complete<br/>save all vectors and metadata"]
    READY[("embedding VECTOR(1536)<br/>provider and model<br/>embedding_status=READY")]
    FAIL_E["REQUIRES_NEW fail<br/>clear all vectors<br/>embedding_status=FAILED"]

    UP --> CHECK --> HASH --> UNIQUE
    UNIQUE -->|yes| DUP
    UNIQUE -->|no| STORE

    STORE -->|explicit API call| PROCESS
    PROCESS --> CLAIM_C --> SPLIT --> VALID_C
    VALID_C -->|yes| COMPLETE_C --> CHUNK_READY
    VALID_C -->|no| FAIL_C
    SPLIT -. exception .-> FAIL_C
    FAIL_C -->|retry process API| PROCESS

    CHUNK_READY -->|explicit API call| EMBED
    EMBED --> CLAIM_E --> BATCH --> OPENAI --> VALID_E
    VALID_E -->|all batches valid| COMPLETE_E --> READY
    VALID_E -->|invalid| FAIL_E
    OPENAI -. provider exception .-> FAIL_E
    FAIL_E -->|retry embedding API| EMBED
~~~

## 이 그림에서 면접관이 봐야 할 핵심 포인트

- 업로드가 외부 AI 호출을 자동으로 시작하지 않으며 Chunking과 Embedding을 독립적으로 실행·재시도한다.
- Chunk와 Vector는 전체 결과가 유효할 때만 완료 처리하고, 실패 시 부분 데이터를 제거해 READY 상태의 일관성을 지킨다.
- Chunking 상태와 Embedding 상태를 분리해 실패 위치와 재처리 범위를 API와 DB에서 확인할 수 있다.

## 코드 근거

[DocumentService](../../src/main/java/com/portfolio/ragobservability/document/DocumentService.java) · [DocumentProcessingService](../../src/main/java/com/portfolio/ragobservability/document/chunk/DocumentProcessingService.java) · [DocumentProcessingTransactions](../../src/main/java/com/portfolio/ragobservability/document/chunk/DocumentProcessingTransactions.java) · [EmbeddingService](../../src/main/java/com/portfolio/ragobservability/document/embedding/EmbeddingService.java) · [EmbeddingTransactions](../../src/main/java/com/portfolio/ragobservability/document/embedding/EmbeddingTransactions.java)
