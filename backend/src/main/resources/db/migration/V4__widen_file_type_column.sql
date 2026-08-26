-- Increase file_type column to accommodate full MIME type strings.
-- Previously VARCHAR(50) was too short for DOCX MIME type:
-- 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' = 71 chars.
ALTER TABLE documents ALTER COLUMN file_type TYPE VARCHAR(100);

