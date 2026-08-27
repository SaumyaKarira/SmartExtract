-- V5__add_validation_fields_to_purchase_orders.sql
-- Adds columns to store the deterministic validation outcome metadata.

ALTER TABLE purchase_orders
    ADD COLUMN IF NOT EXISTS validation_corrections      TEXT,
    ADD COLUMN IF NOT EXISTS validation_review_reasons   TEXT;

