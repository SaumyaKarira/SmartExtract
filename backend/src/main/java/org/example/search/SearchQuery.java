package org.example.search;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Structured search criteria extracted from a natural-language query.
 * All fields are optional (null means "no filter on this field").
 */
public record SearchQuery(
        String poNumber,
        String supplier,
        String itemDescription,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        LocalDate dateFrom,
        LocalDate dateTo,
        String status,
        String sortBy,          // "date" | "amount" | "poNumber" | "supplier"
        String sortDir,         // "asc" | "desc"
        int page,
        int pageSize,
        boolean amountInclusive // true = >=/<= (filter form), false = >/<  (NL "above/below")
) {
    /** Canonical defaults — NL search uses strict comparisons */
    public static SearchQuery defaults() {
        return new SearchQuery(null, null, null, null, null, null, null, null,
                "date", "desc", 0, 20, false);
    }
}

