-- V2__add_file_hash_to_documents.sql
-- Add SHA-256 hash column and unique constraint per user

ALTER TABLE documents ADD COLUMN file_hash VARCHAR(64);

CREATE UNIQUE INDEX uq_documents_user_hash ON documents (user_id, file_hash)
    WHERE file_hash IS NOT NULL;

