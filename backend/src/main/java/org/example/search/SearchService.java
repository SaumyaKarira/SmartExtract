package org.example.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final RuleBasedQueryParser ruleParser;
    private final GeminiSearchParser geminiParser;
    private final PurchaseOrderSearchService searchService;

    public SearchService(RuleBasedQueryParser ruleParser,
                         GeminiSearchParser geminiParser,
                         PurchaseOrderSearchService searchService) {
        this.ruleParser = ruleParser;
        this.geminiParser = geminiParser;
        this.searchService = searchService;
    }

    public SearchResponse search(String rawQuery, Long userId) {
        if (rawQuery == null || rawQuery.isBlank()) {
            SearchQuery all = SearchQuery.defaults();
            SearchResponse r = searchService.search(all, userId);
            return new SearchResponse("all purchase orders", "rules", r.totalResults(), r.page(), r.pageSize(), r.results());
        }

        // 1) Try rule-based parser first
        var ruleResult = ruleParser.parse(rawQuery);
        if (ruleResult.isPresent()) {
            log.debug("Rule-based parser handled query: {}", rawQuery);
            SearchQuery q = ruleResult.get();
            SearchResponse r = searchService.search(q, userId);
            return new SearchResponse(r.parsedQuery(), "rules", r.totalResults(), r.page(), r.pageSize(), r.results());
        }

        // 2) Fall back to Gemini
        log.debug("Falling back to Gemini for query: {}", rawQuery);
        try {
            SearchQuery q = geminiParser.parse(rawQuery);
            SearchResponse r = searchService.search(q, userId);
            return new SearchResponse(r.parsedQuery(), "gemini", r.totalResults(), r.page(), r.pageSize(), r.results());
        } catch (Exception e) {
            log.warn("Gemini search parsing failed, falling back to supplier search: {}", e.getMessage());
            // Last resort: treat the whole query as a keyword search across supplier + poNumber
            SearchQuery fallback = new SearchQuery(
                    rawQuery, rawQuery, rawQuery,
                    null, null, null, null, null, "date", "desc", 0, 20, false);
            SearchResponse r = searchService.search(fallback, userId);
            return new SearchResponse("keyword: \"" + rawQuery + "\"", "fallback",
                    r.totalResults(), r.page(), r.pageSize(), r.results());
        }
    }
}

