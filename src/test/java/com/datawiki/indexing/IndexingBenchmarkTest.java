package com.datawiki.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import com.datawiki.documents.DocumentService.DocumentMeta;
import com.datawiki.markdown.MarkdownParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Measures parse + index throughput on synthetic Markdown (Zipf-like word frequencies, mixed RU/EN) and prints
 * the result to stdout and {@code target/benchmark.txt}. Assertions are loose: they only catch order-of-magnitude
 * regressions. Numbers depend on the machine; the report in docs/benchmark.md says which one was used.
 */
class IndexingBenchmarkTest {

    static final int DOCS = Integer.getInteger("benchmark.docs", 5000);
    static final int WORDS_PER_DOC = 400;

    final Random random = new Random(42);
    final String[] vocabulary = vocabulary(3000);

    @Test
    void indexingThroughput(@TempDir Path dir) throws IOException {
        var parser = new MarkdownParser(10_000_000);
        var owner = UUID.randomUUID();
        List<String> docs = java.util.stream.IntStream.range(0, DOCS).mapToObj(this::markdown).toList();
        long bytes = docs.stream().mapToLong(d -> d.getBytes().length).sum();

        try (var index = new LuceneIndex(dir, new WikiAnalyzer(true))) {
            long t0 = System.nanoTime();
            for (String md : docs) {
                var parsed = parser.parse(md, "note.md");
                var meta = new DocumentMeta(UUID.randomUUID(), parsed.title(), parsed.tags(), parsed.category(),
                        "bench", 1, parsed.sizeBytes(), parsed.wordCount(), Instant.now(), Instant.now());
                index.upsert(owner, meta, parsed);
            }
            long t1 = System.nanoTime();
            index.commit();
            long t2 = System.nanoTime();

            String term = "kalor"; // any vocabulary word; the analyzer stems it the same way on both sides
            int rounds = 200;
            long q0 = System.nanoTime();
            for (int i = 0; i < rounds; i++) {
                index.search(s -> s.search(new TermQuery(new Term(LuceneIndex.BODY, term)), 10).totalHits.value());
            }
            double queryMs = (System.nanoTime() - q0) / 1e6 / rounds;

            double seconds = (t2 - t0) / 1e9;
            String report = "docs=%d avgWords=%d totalMB=%.1f parse+index=%.2fs commit=%.2fs total=%.2fs %.0f docs/s %.1f MB/s query(term)=%.3f ms indexDocs=%d"
                    .formatted(DOCS, WORDS_PER_DOC, bytes / 1e6, (t1 - t0) / 1e9, (t2 - t1) / 1e9, seconds,
                            DOCS / seconds, bytes / 1e6 / seconds, queryMs, index.size());
            System.out.println("BENCHMARK " + report);
            Files.writeString(Path.of("target", "benchmark.txt"), report + System.lineSeparator());

            assertThat(index.size()).isEqualTo(DOCS);
            assertThat(DOCS / seconds).as("docs/s").isGreaterThan(50);
        }
    }

    private String markdown(int i) {
        var sb = new StringBuilder("---\ntags: [t").append(i % 20).append(", t").append(i % 7)
                .append("]\ncategory: c").append(i % 5).append("\n---\n# ").append(word()).append(' ').append(word())
                .append("\n\n");
        for (int w = 0; w < WORDS_PER_DOC; w++) {
            sb.append(word()).append(w % 15 == 14 ? "\n\n" : " ");
        }
        return sb.toString();
    }

    /** Zipf-like: low ranks are picked far more often. */
    private String word() {
        return vocabulary[(int) (vocabulary.length * Math.pow(random.nextDouble(), 3))];
    }

    private String[] vocabulary(int n) {
        Random r = new Random(7);
        String[] ru = {"ка", "ло", "ри", "на", "ст", "ре", "по", "ми", "ту", "ле"};
        String[] en = {"ka", "lo", "ri", "na", "st", "re", "po", "mi", "tu", "le"};
        String[] words = new String[n];
        words[0] = "kalor";
        for (int i = 1; i < n; i++) {
            String[] syllables = i % 2 == 0 ? ru : en;
            var w = new StringBuilder();
            for (int s = 0, len = 2 + r.nextInt(3); s < len; s++) {
                w.append(syllables[r.nextInt(syllables.length)]);
            }
            words[i] = w.toString();
        }
        return words;
    }
}
