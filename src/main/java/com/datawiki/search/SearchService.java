package com.datawiki.search;

import static com.datawiki.indexing.LuceneIndex.AUTHOR;
import static com.datawiki.indexing.LuceneIndex.BODY;
import static com.datawiki.indexing.LuceneIndex.CATEGORY;
import static com.datawiki.indexing.LuceneIndex.CODE;
import static com.datawiki.indexing.LuceneIndex.HEADINGS;
import static com.datawiki.indexing.LuceneIndex.ID;
import static com.datawiki.indexing.LuceneIndex.OWNER_ID;
import static com.datawiki.indexing.LuceneIndex.SIZE_BYTES;
import static com.datawiki.indexing.LuceneIndex.TAGS;
import static com.datawiki.indexing.LuceneIndex.TAGS_TEXT;
import static com.datawiki.indexing.LuceneIndex.TITLE;
import static com.datawiki.indexing.LuceneIndex.UPDATED_AT;

import com.datawiki.documents.DocumentService;
import com.datawiki.documents.DocumentService.DocumentMeta;
import com.datawiki.indexing.LuceneIndex;
import com.datawiki.indexing.WikiAnalyzer;
import com.datawiki.scoring.ScoringMethod.Scored;
import com.datawiki.scoring.ScoringRegistry;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.LongField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortedNumericSelector;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.uhighlight.DefaultPassageFormatter;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.springframework.stereotype.Service;

/**
 * Full-text search over one user's documents: analyzed query over boosted fields, filters, scoring method,
 * sorting, paging and highlighting. Every query carries an {@code owner_id} filter.
 */
@Service
public class SearchService {

    public enum SortBy { RELEVANCE, UPDATED_AT, SIZE_BYTES }

    /**
     * @param method  scoring method name; {@code null} means bm25
     * @param weights hybrid weights (need not sum to 1); {@code null} means equal
     * @param tags    the document must have all of them
     * @param page    from 0
     */
    public record SearchRequest(String q, String method, Map<String, Double> weights, List<String> tags,
                                String category, String author, Instant updatedFrom, Instant updatedTo,
                                SortBy sort, int page, int size) {
    }

    public record Highlight(String field, String snippet) {
    }

    /** {@code score} is 0 when sorting by something other than relevance. */
    public record Hit(DocumentMeta document, float score, List<Highlight> highlights) {
    }

    public record SearchResult(String method, long total, int page, int size, List<Hit> hits, long tookMs) {
    }

    private static final String DEFAULT_METHOD = "bm25";
    private static final int MAX_QUERY_LENGTH = 500;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int HIGHLIGHT_LIMIT = 200_000;
    /** Field boosts from docs/scoring.md. */
    private static final Map<String, Float> BOOSTS = Map.of(
            TITLE, 3f, HEADINGS, 2f, TAGS_TEXT, 2f, BODY, 1f, CODE, 0.5f);
    private static final String[] HIGHLIGHT_FIELDS = {TITLE, HEADINGS, BODY};
    private static final int[] HIGHLIGHT_PASSAGES = {1, 1, 2};

    private final LuceneIndex index;
    private final WikiAnalyzer analyzer;
    private final ScoringRegistry scoring;
    private final DocumentService documents;

    public SearchService(LuceneIndex index, WikiAnalyzer analyzer, ScoringRegistry scoring, DocumentService documents) {
        this.index = index;
        this.analyzer = analyzer;
        this.scoring = scoring;
        this.documents = documents;
    }

    /** @throws IllegalArgumentException invalid request (blank or too long query, bad paging, unknown method, bad weights) */
    public SearchResult search(UUID ownerId, SearchRequest request) {
        long started = System.nanoTime();
        String method = request.method() == null ? DEFAULT_METHOD : request.method();
        validate(request, method);
        SortBy sortBy = request.sort() == null ? SortBy.RELEVANCE : request.sort();

        List<String> terms = terms(request.q());
        if (terms.isEmpty()) { // only stop words: nothing to match, and a filter-only query would match everything
            return new SearchResult(method, 0, request.page(), request.size(), List.of(), millisSince(started));
        }
        Query query = buildQuery(ownerId, terms, request);

        Found found = index.search(searcher -> find(searcher, query, method, request, sortBy));
        List<UUID> ids = found.candidates().stream().map(Candidate::id).toList();
        Map<UUID, DocumentMeta> metas = documents.metas(ownerId, ids);
        // documents deleted after the last index commit are dropped here
        List<Hit> hits = found.candidates().stream()
                .filter(c -> metas.containsKey(c.id()))
                .map(c -> new Hit(metas.get(c.id()), c.score(), c.highlights()))
                .toList();
        return new SearchResult(method, found.total(), request.page(), request.size(), hits, millisSince(started));
    }

    private record Candidate(UUID id, float score, List<Highlight> highlights) {
    }

    private record Found(long total, List<Candidate> candidates) {
    }

    private Found find(IndexSearcher searcher, Query query, String method, SearchRequest request, SortBy sortBy)
            throws IOException {
        int upTo = (request.page() + 1) * request.size();
        List<Scored> ranked;
        if (sortBy == SortBy.RELEVANCE) {
            ranked = scoring.search(method, request.weights(), searcher.getIndexReader(), query, upTo);
        } else {
            ranked = new ArrayList<>();
            for (var d : searcher.search(query, upTo, sortFor(sortBy)).scoreDocs) {
                ranked.add(new Scored(d.doc, 0));
            }
        }
        int from = Math.min(request.page() * request.size(), ranked.size());
        List<Scored> pageDocs = ranked.subList(from, Math.min(upTo, ranked.size()));

        int[] docIds = pageDocs.stream().mapToInt(Scored::doc).toArray();
        Map<String, String[]> highlights = highlight(searcher, query, docIds);
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < docIds.length; i++) {
            List<Highlight> marks = new ArrayList<>();
            for (String field : HIGHLIGHT_FIELDS) {
                String snippet = highlights.get(field)[i];
                // the highlighter falls back to the start of the field when nothing matched; drop those
                if (snippet != null && snippet.contains("<mark>")) {
                    marks.add(new Highlight(field, snippet));
                }
            }
            UUID id = UUID.fromString(searcher.storedFields().document(docIds[i], Set.of(ID)).get(ID));
            candidates.add(new Candidate(id, pageDocs.get(i).score(), marks));
        }
        return new Found(searcher.count(query), candidates);
    }

    /** Snippets wrapped in {@code <mark>}; the text itself is HTML-escaped by the formatter. */
    private Map<String, String[]> highlight(IndexSearcher searcher, Query query, int[] docIds) throws IOException {
        if (docIds.length == 0) {
            return Map.of(TITLE, new String[0], HEADINGS, new String[0], BODY, new String[0]);
        }
        var highlighter = UnifiedHighlighter.builder(searcher, analyzer)
                .withFormatter(new DefaultPassageFormatter("<mark>", "</mark>", "… ", true))
                .withMaxLength(HIGHLIGHT_LIMIT)
                .build();
        return highlighter.highlightFields(HIGHLIGHT_FIELDS, query, docIds, HIGHLIGHT_PASSAGES);
    }

    private Query buildQuery(UUID ownerId, List<String> terms, SearchRequest request) {
        BooleanQuery.Builder text = new BooleanQuery.Builder();
        for (String term : terms) {
            BOOSTS.forEach((field, boost) ->
                    text.add(new BoostQuery(new TermQuery(new Term(field, term)), boost), Occur.SHOULD));
        }
        BooleanQuery.Builder query = new BooleanQuery.Builder();
        query.add(text.build(), Occur.MUST);
        query.add(new TermQuery(new Term(OWNER_ID, ownerId.toString())), Occur.FILTER);
        if (request.tags() != null) {
            request.tags().forEach(tag -> query.add(new TermQuery(new Term(TAGS, tag)), Occur.FILTER));
        }
        if (request.category() != null) {
            query.add(new TermQuery(new Term(CATEGORY, request.category())), Occur.FILTER);
        }
        if (request.author() != null) {
            query.add(new TermQuery(new Term(AUTHOR, request.author())), Occur.FILTER);
        }
        if (request.updatedFrom() != null || request.updatedTo() != null) {
            long from = request.updatedFrom() == null ? Long.MIN_VALUE : request.updatedFrom().toEpochMilli();
            long to = request.updatedTo() == null ? Long.MAX_VALUE : request.updatedTo().toEpochMilli();
            query.add(LongField.newRangeQuery(UPDATED_AT, from, to), Occur.FILTER);
        }
        return query.build();
    }

    /** Analyzed query terms without duplicates, in order. */
    private List<String> terms(String q) {
        Set<String> terms = new LinkedHashSet<>();
        try (var stream = analyzer.tokenStream(BODY, q)) {
            var term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                terms.add(term.toString());
            }
            stream.end();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return List.copyOf(terms);
    }

    private static Sort sortFor(SortBy sortBy) {
        String field = sortBy == SortBy.UPDATED_AT ? UPDATED_AT : SIZE_BYTES;
        return new Sort(LongField.newSortField(field, true, SortedNumericSelector.Type.MAX), SortField.FIELD_DOC);
    }

    private void validate(SearchRequest request, String method) {
        if (request.q() == null || request.q().isBlank()) {
            throw new IllegalArgumentException("Query must not be blank");
        }
        if (request.q().length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("Query is longer than " + MAX_QUERY_LENGTH + " characters");
        }
        if (request.page() < 0 || request.size() < 1 || request.size() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page must be >= 0 and size within 1.." + MAX_PAGE_SIZE);
        }
        if (!scoring.isKnown(method)) {
            throw new IllegalArgumentException("Unknown scoring method: " + method);
        }
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
