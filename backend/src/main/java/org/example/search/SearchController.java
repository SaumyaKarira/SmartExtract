package org.example.search;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;
    private final PurchaseOrderSearchService poSearchService;

    public SearchController(SearchService searchService,
                            PurchaseOrderSearchService poSearchService) {
        this.searchService = searchService;
        this.poSearchService = poSearchService;
    }

    /** Natural-language search (rule-based → Gemini fallback) */
    @PostMapping
    public SearchResponse search(@RequestBody SearchRequest request,
                                  Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return searchService.search(request.query(), userId);
    }

    /** Structured filter search — never calls Gemini */
    @PostMapping("/filter")
    public SearchResponse filter(@RequestBody FilterRequest req,
                                  Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        SearchQuery q = new SearchQuery(
                null,
                req.supplier(),
                null,
                req.minAmount() != null ? new BigDecimal(req.minAmount()) : null,
                req.maxAmount() != null ? new BigDecimal(req.maxAmount()) : null,
                req.dateFrom() != null ? LocalDate.parse(req.dateFrom()) : null,
                req.dateTo()   != null ? LocalDate.parse(req.dateTo())   : null,
                req.status(),
                "date", "desc",
                req.page() != null ? req.page() : 0,
                20
        );
        SearchResponse r = poSearchService.search(q, userId);
        return new SearchResponse(r.parsedQuery(), "filter", r.totalResults(), r.page(), r.pageSize(), r.results());
    }

    /** Returns the distinct supplier names for the authenticated user */
    @GetMapping("/suppliers")
    public List<String> suppliers(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return poSearchService.distinctSuppliers(userId);
    }
}

