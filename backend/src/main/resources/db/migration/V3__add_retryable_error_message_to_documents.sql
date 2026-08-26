-- V3__add_retryable_error_message_to_documents.sql
-- Add retryable flag and user-facing error message to documents

ALTER TABLE documents
    ADD COLUMN retryable     BOOLEAN      DEFAULT FALSE,
    ADD COLUMN error_message VARCHAR(500);

