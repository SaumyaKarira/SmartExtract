package org.example.search;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping
    public SearchResponse search(@RequestBody SearchRequest request,
                                  Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        return searchService.search(request.query(), userId);
    }
}

