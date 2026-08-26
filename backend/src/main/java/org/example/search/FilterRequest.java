package org.example.search;

/** Structured filter request from the frontend filter panel (never calls Gemini). */
public record FilterRequest(
        String supplier,
        String status,
        String minAmount,
        String maxAmount,
        String dateFrom,
        String dateTo,
        Integer page
) {}

