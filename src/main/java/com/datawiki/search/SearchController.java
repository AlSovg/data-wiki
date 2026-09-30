package com.datawiki.search;

import static com.datawiki.auth.CurrentUser.id;

import com.datawiki.scoring.ScoringRegistry;
import com.datawiki.search.SearchService.SearchRequest;
import com.datawiki.search.SearchService.SearchResult;
import com.datawiki.search.SearchService.SortBy;
import com.datawiki.search.SearchSettingsService.Settings;
import java.security.Principal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class SearchController {

    private final SearchService search;
    private final SearchSettingsService settings;
    private final ScoringRegistry scoring;

    SearchController(SearchService search, SearchSettingsService settings, ScoringRegistry scoring) {
        this.search = search;
        this.settings = settings;
        this.scoring = scoring;
    }

    /** Without {@code method} the user's saved settings apply; with it, only the request's own weights do. */
    @GetMapping("/search")
    SearchResult search(Principal user,
                        @RequestParam String q,
                        @RequestParam(required = false) String method,
                        @RequestParam(required = false) String weights,
                        @RequestParam(required = false) List<String> tags,
                        @RequestParam(required = false) String category,
                        @RequestParam(required = false) String author,
                        @RequestParam(name = "updated_from", required = false)
                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant updatedFrom,
                        @RequestParam(name = "updated_to", required = false)
                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant updatedTo,
                        @RequestParam(defaultValue = "relevance") String sort,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "20") int size) {
        Map<String, Double> parsedWeights = parseWeights(weights);
        if (method == null) {
            Settings saved = settings.get(id(user));
            method = saved.method();
            if (parsedWeights == null) {
                parsedWeights = saved.weights();
            }
        }
        return search.search(id(user), new SearchRequest(q, method, parsedWeights, tags, category, author,
                updatedFrom, updatedTo, sortBy(sort), page, size));
    }

    @GetMapping("/search/methods")
    List<String> methods() {
        return scoring.names();
    }

    @GetMapping("/settings/search")
    Settings getSettings(Principal user) {
        return settings.get(id(user));
    }

    @PutMapping("/settings/search")
    Settings putSettings(Principal user, @RequestBody Settings body) {
        return settings.save(id(user), body);
    }

    /** {@code bm25:0.7,tfidf:0.3}; the number format errors surface as 400. */
    private static Map<String, Double> parseWeights(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Map<String, Double> weights = new LinkedHashMap<>();
        for (String pair : text.split(",")) {
            String[] parts = pair.split(":");
            if (parts.length != 2) {
                throw new IllegalArgumentException("weights must look like bm25:0.7,tfidf:0.3");
            }
            weights.put(parts[0].strip(), Double.valueOf(parts[1].strip()));
        }
        return weights;
    }

    private static SortBy sortBy(String sort) {
        return switch (sort) {
            case "relevance" -> SortBy.RELEVANCE;
            case "updated_at" -> SortBy.UPDATED_AT;
            case "size_bytes" -> SortBy.SIZE_BYTES;
            default -> throw new IllegalArgumentException("sort must be relevance, updated_at or size_bytes");
        };
    }
}
