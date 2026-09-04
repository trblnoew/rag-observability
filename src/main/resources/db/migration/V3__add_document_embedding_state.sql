ALTER TABLE document
    ADD COLUMN embedding_status VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED',
    ADD COLUMN embedding_error_code VARCHAR(100),
    ADD COLUMN embedding_error_message TEXT,
    ADD COLUMN embedding_retry_count INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_document_embedding_status
        CHECK (embedding_status IN ('NOT_STARTED', 'PENDING', 'PROCESSING', 'READY', 'FAILED')),
    ADD CONSTRAINT ck_document_embedding_retry_count
        CHECK (embedding_retry_count >= 0);

UPDATE document AS d
SET embedding_status = CASE
    WHEN EXISTS (
        SELECT 1 FROM document_chunk AS c WHERE c.document_id = d.id
    ) AND NOT EXISTS (
        SELECT 1
        FROM document_chunk AS c
        WHERE c.document_id = d.id AND c.embedding IS NULL
    ) THEN 'READY'
    WHEN EXISTS (
        SELECT 1 FROM document_chunk AS c WHERE c.document_id = d.id
    ) THEN 'PENDING'
    ELSE 'NOT_STARTED'
END;
