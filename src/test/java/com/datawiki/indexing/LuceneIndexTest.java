package com.datawiki.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import com.datawiki.documents.DocumentService.DocumentMeta;
import com.datawiki.markdown.MarkdownParser;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LuceneIndexTest {

    final WikiAnalyzer analyzer = new WikiAnalyzer(true);
    final MarkdownParser parser = new MarkdownParser(1_000_000);
    final UUID owner = UUID.randomUUID();
    LuceneIndex index;

    @BeforeEach
    void open(@TempDir Path dir) throws IOException {
        index = new LuceneIndex(dir, analyzer);
    }

    @AfterEach
    void close() throws IOException {
        index.close();
    }

    List<String> tokens(String text) throws IOException {
        List<String> result = new ArrayList<>();
        try (var stream = analyzer.tokenStream(LuceneIndex.BODY, text)) {
            var term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                result.add(term.toString());
            }
            stream.end();
        }
        return result;
    }

    UUID add(String markdown, String... tags) {
        var parsed = parser.parse(markdown, "note.md");
        var id = UUID.randomUUID();
        var meta = new DocumentMeta(id, parsed.title(), List.of(tags), null, "alice", 1, parsed.sizeBytes(),
                parsed.wordCount(), Instant.now(), Instant.now());
        index.upsert(owner, meta, parsed);
        return id;
    }

    /** Documents matching all analyzed terms of {@code text} in {@code field}. */
    List<String> find(String field, String text) throws IOException {
        BooleanQuery.Builder query = new BooleanQuery.Builder();
        for (String token : tokens(text)) {
            query.add(new TermQuery(new Term(field, token)), Occur.MUST);
        }
        return run(query.build());
    }

    List<String> run(Query query) {
        return index.search(s -> {
            List<String> ids = new ArrayList<>();
            for (var hit : s.search(query, 10).scoreDocs) {
                ids.add(s.storedFields().document(hit.doc).get(LuceneIndex.ID));
            }
            return ids;
        });
    }

    @Test
    void analyzerStemsRussianAndEnglishAndDropsStopWords() throws IOException {
        assertThat(tokens("Документы и документов")).containsExactly("документ", "документ");
        assertThat(tokens("The running runs")).containsExactly("run", "run");
        assertThat(tokens("Поиск search-engine")).containsExactly("поиск", "search", "engin");
    }

    @Test
    void stemmingCanBeDisabled() throws IOException {
        var plain = new WikiAnalyzer(false);
        try (var stream = plain.tokenStream(LuceneIndex.BODY, "документы")) {
            var term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            assertThat(stream.incrementToken()).isTrue();
            assertThat(term.toString()).isEqualTo("документы");
        }
    }

    @Test
    void findsDocumentByAnyWordForm() throws IOException {
        var id = add("# Заметка\n\nИндексация документов работает быстро");
        add("# Other\n\nunrelated words here");
        index.commit();

        assertThat(find(LuceneIndex.BODY, "документ")).containsExactly(id.toString());
        assertThat(find(LuceneIndex.TITLE, "заметки")).containsExactly(id.toString());
    }

    @Test
    void upsertReplacesAndDeleteRemoves() throws IOException {
        var parsed = parser.parse("# T\n\nfirst text", "note.md");
        var id = UUID.randomUUID();
        var meta = new DocumentMeta(id, "T", List.of("a", "b"), "cat", "alice", 1, 1, 2, Instant.now(), Instant.now());
        index.upsert(owner, meta, parsed);
        index.upsert(owner, meta, parser.parse("# T\n\nsecond text", "note.md"));
        index.commit();

        assertThat(index.size()).isEqualTo(1);
        assertThat(find(LuceneIndex.BODY, "first")).isEmpty();
        assertThat(find(LuceneIndex.BODY, "second")).containsExactly(id.toString());
        assertThat(run(new TermQuery(new Term(LuceneIndex.TAGS, "b")))).containsExactly(id.toString());
        assertThat(run(new TermQuery(new Term(LuceneIndex.OWNER_ID, owner.toString())))).containsExactly(id.toString());

        index.delete(id);
        index.commit();

        assertThat(index.size()).isZero();
    }

    @Test
    void changesAreInvisibleUntilCommit() throws IOException {
        add("# T\n\nsomething");

        assertThat(index.size()).isZero();
        index.commit();
        assertThat(index.size()).isEqualTo(1);
    }
}
