package org.example.search;

import org.example.purchaseorder.PurchaseOrderResponse;

import java.util.List;

public record SearchResponse(
        String parsedQuery,          // human-readable description of what was searched
        String resolvedBy,           // "rules" | "gemini"
        int totalResults,
        int page,
        int pageSize,
        List<PurchaseOrderResponse> results
) {}

