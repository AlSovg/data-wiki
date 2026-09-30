package com.datawiki.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.datawiki.documents.DocumentService.DocumentMeta;
import com.datawiki.indexing.LuceneIndex;
import com.datawiki.indexing.WikiAnalyzer;
import com.datawiki.markdown.MarkdownParser;
import com.datawiki.scoring.ScoringMethod.Scored;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks the formulas of docs/scoring.md against Lucene's numbers on a four-document corpus. */
class ScoringTest {

    final ScoringRegistry registry = new ScoringRegistry(List.of(
            new ScoringConfig().bm25(1.2f, 0.75f), new ScoringConfig().tfidf()));
    final Map<Integer, String> bodies = new HashMap<>();
    LuceneIndex index;
    UUID owner = UUID.randomUUID();

    @BeforeEach
    void corpus(@TempDir Path dir) throws IOException {
        index = new LuceneIndex(dir, new WikiAnalyzer(true));
        MarkdownParser parser = new MarkdownParser(1_000_000);
        // body lengths 2, 5, 2, 6 -> avgdl 3.75; "cat" is in two documents; words are stem-stable
        for (String body : List.of("cat dog", "cat cat cat fish bird", "dog fish", "bird bird bird bird bird bird")) {
            var parsed = parser.parse("# T\n\n" + body, "n.md");
            var meta = new DocumentMeta(UUID.randomUUID(), "T", List.of(), null, "a", 1, 1, 1, Instant.now(), Instant.now());
            index.upsert(owner, meta, parsed);
        }
        index.commit();
    }

    @AfterEach
    void close() throws IOException {
        index.close();
    }

    /** Score of the document whose body is {@code body}, for a one-term query on the body field. */
    float score(String method, String term, String body) {
        return index.search(s -> {
            var hits = registry.search(method, null, s.getIndexReader(), new TermQuery(new Term(LuceneIndex.BODY, term)), 10);
            for (Scored hit : hits) {
                if (s.storedFields().document(hit.doc()).get(LuceneIndex.BODY).equals(body)) {
                    return hit.score();
                }
            }
            throw new AssertionError("no hit for " + body);
        });
    }

    @Test
    void bm25MatchesFormula() {
        double n = 4, df = 2, f = 3, dl = 5, avgdl = 3.75, k1 = 1.2, b = 0.75;
        double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
        double expected = idf * f / (f + k1 * (1 - b + b * dl / avgdl));

        assertThat(score("bm25", "cat", "cat cat cat fish bird")).isCloseTo((float) expected, within(1e-4f));
    }

    @Test
    void tfidfMatchesFormula() {
        double n = 4, df = 2, f = 3, dl = 5;
        double expected = (Math.log((n + 1) / (df + 1)) + 1) * Math.sqrt(f) / Math.sqrt(dl);

        assertThat(score("tfidf", "cat", "cat cat cat fish bird")).isCloseTo((float) expected, within(1e-4f));
    }

    @Test
    void hybridNormalizesEachListAndSumsWeightedScores() {
        var bm25 = List.of(new Scored(1, 10), new Scored(2, 6), new Scored(3, 2));
        var tfidf = List.of(new Scored(2, 4), new Scored(3, 2));

        var result = ScoringRegistry.combine(Map.of("bm25", bm25, "tfidf", tfidf), Map.of("bm25", 0.5, "tfidf", 0.5));

        // bm25 -> 1:1, 2:0.5, 3:0; tfidf -> 2:1, 3:0; document 1 is absent from tfidf and gets 0 from it
        assertThat(result).extracting(Scored::doc).containsExactly(2, 1, 3);
        assertThat(result.get(0).score()).isCloseTo(0.75f, within(1e-6f));
        assertThat(result.get(1).score()).isCloseTo(0.5f, within(1e-6f));
        assertThat(result.get(2).score()).isZero();
    }

    @Test
    void equalScoresNormalizeToOne() {
        var result = ScoringRegistry.combine(Map.of("bm25", List.of(new Scored(7, 3.3f))), Map.of("bm25", 1.0));

        assertThat(result).containsExactly(new Scored(7, 1f));
    }

    @Test
    void hybridSearchRanksBestDocumentFirst() {
        var top = index.search(s -> {
            var hits = registry.search("hybrid", null, s.getIndexReader(), new TermQuery(new Term(LuceneIndex.BODY, "cat")), 10);
            return s.storedFields().document(hits.get(0).doc()).get(LuceneIndex.BODY);
        });

        assertThat(top).isEqualTo("cat cat cat fish bird");
    }

    @Test
    void weightsAreScaledToSumOne() {
        assertThat(registry.normalize(Map.of("bm25", 3.0, "tfidf", 1.0)))
                .containsEntry("bm25", 0.75).containsEntry("tfidf", 0.25);
    }

    @Test
    void badWeightsAndMethodsAreRejected() {
        assertThatThrownBy(() -> registry.normalize(Map.of("bm25", -1.0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.normalize(Map.of("bm25", 0.0, "tfidf", 0.0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.normalize(Map.of("magic", 1.0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.search("magic", null, null, null, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(registry.names()).containsExactly("bm25", "tfidf", "hybrid");
    }
}
