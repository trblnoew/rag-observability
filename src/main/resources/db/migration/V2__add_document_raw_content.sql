ALTER TABLE document
    ADD COLUMN raw_content TEXT NOT NULL;

ALTER TABLE document
    ADD CONSTRAINT ck_document_source_type
        CHECK (source_type IN ('TXT', 'MARKDOWN'));
