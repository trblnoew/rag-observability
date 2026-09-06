# 주요 DB 관계

~~~mermaid
erDiagram
    DOCUMENT ||--o{ DOCUMENT_CHUNK : "ON DELETE CASCADE"

    DOCUMENT {
        bigint id PK
        varchar title
        varchar original_filename
        varchar source_type
        string content_sha256 UK
        text raw_content
        varchar status
        varchar error_code
        int retry_count
        varchar embedding_status
        varchar embedding_error_code
        int embedding_retry_count
        timestamptz created_at
        timestamptz updated_at
    }

    DOCUMENT_CHUNK {
        bigint id PK
        bigint document_id FK
        int chunk_index
        text content
        int token_count
        varchar embedding_provider
        varchar embedding_model
        vector_1536 embedding
        timestamptz created_at
    }
~~~

## 이 그림에서 면접관이 봐야 할 핵심 포인트

- document와 document_chunk는 1:N이며 문서 삭제 시 Chunk도 cascade 삭제되고, 문서별 chunk_index는 unique하다.
- 원문 SHA-256 unique 제약으로 중복 업로드를 막고 Chunking 상태와 Embedding 상태·오류·retry를 따로 저장한다.
- Vector는 document_chunk의 VECTOR(1536)에 provider/model과 함께 저장하며 현재 Vector ANN index는 없다.

## 코드 근거

[Flyway V1](../../src/main/resources/db/migration/V1__create_document_schema.sql) · [Flyway V2](../../src/main/resources/db/migration/V2__add_document_raw_content.sql) · [Flyway V3](../../src/main/resources/db/migration/V3__add_document_embedding_state.sql) · [RetrievalRepository](../../src/main/java/com/portfolio/ragobservability/retrieval/RetrievalRepository.java) · [EmbeddingStateMigrationTests](../../src/test/java/com/portfolio/ragobservability/document/embedding/EmbeddingStateMigrationTests.java)

