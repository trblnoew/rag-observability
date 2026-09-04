CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE document (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    content_sha256 CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_code VARCHAR(100),
    error_message TEXT,
    retry_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_document_content_sha256 UNIQUE (content_sha256),
    CONSTRAINT ck_document_status CHECK (status IN ('PENDING', 'PROCESSING', 'READY', 'FAILED')),
    CONSTRAINT ck_document_retry_count CHECK (retry_count >= 0)
);

CREATE TABLE document_chunk (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    token_count INTEGER,
    embedding_provider VARCHAR(100),
    embedding_model VARCHAR(150),
    embedding VECTOR(1536),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_document_chunk_index UNIQUE (document_id, chunk_index),
    CONSTRAINT ck_document_chunk_index CHECK (chunk_index >= 0),
    CONSTRAINT ck_document_chunk_token_count CHECK (token_count IS NULL OR token_count >= 0)
);

CREATE INDEX idx_document_status ON document (status);
CREATE INDEX idx_document_chunk_document_id ON document_chunk (document_id);

