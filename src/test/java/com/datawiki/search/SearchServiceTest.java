package com.datawiki.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datawiki.documents.DocumentService;
import com.datawiki.indexing.IndexWorker;
import com.datawiki.indexing.LuceneIndex;
import com.datawiki.markdown.MarkdownParser;
import com.datawiki.search.SearchService.Hit;
import com.datawiki.search.SearchService.SearchRequest;
import com.datawiki.search.SearchService.SortBy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
class SearchServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    SearchService search;
    @Autowired
    DocumentService documents;
    @Autowired
    IndexWorker worker;
    @Autowired
    LuceneIndex index;
    @Autowired
    MarkdownParser parser;
    @Autowired
    JdbcClient jdbc;

    UUID alice;
    UUID bob;

    @BeforeEach
    void reset() {
        jdbc.sql("truncate users, tags cascade").update();
        index.clear();
        index.commit();
        alice = user("alice@example.com");
        bob = user("bob@example.com");
    }

    private UUID user(String email) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into users (id, email, password_hash) values (:id, :email, 'x')")
                .param("id", id).param("email", email).update();
        return id;
    }

    private UUID add(UUID owner, String markdown) {
        UUID id = documents.create(owner, parser.parse(markdown, "note.md")).id();
        worker.processPending();
        return id;
    }

    private SearchRequest q(String text) {
        return new SearchRequest(text, null, null, null, null, null, null, null, null, 0, 10);
    }

    private List<UUID> ids(SearchService.SearchResult result) {
        return result.hits().stream().map(h -> h.document().id()).toList();
    }

    @Test
    void findsAnyWordFormAndPutsTitleMatchFirst() {
        var inBody = add(alice, "# Заметка\n\nМы обсуждали развёртывание кластера kubernetes вчера");
        var inTitle = add(alice, "# Kubernetes\n\nСовсем другой текст без нужного слова");
        add(alice, "# Другое\n\nничего общего");

        for (String method : List.of("bm25", "tfidf", "hybrid")) {
            var result = search.search(alice, new SearchRequest("Kubernetes кластеров", method, null, null, null, null,
                    null, null, null, 0, 10));
            assertThat(ids(result)).as(method).containsExactly(inTitle, inBody);
            assertThat(result.total()).isEqualTo(2);
        }
    }

    @Test
    void secondIdenticalSearchComesFromCache() {
        add(alice, "# Cache\n\nredis cache test");

        assertThat(search.search(alice, q("cache")).cached()).isFalse();
        assertThat(search.search(alice, q("Cache")).cached()).isTrue(); // same analyzed terms
        assertThat(search.search(bob, q("cache")).cached()).isFalse(); // other user, other key
    }

    @Test
    void indexCommitInvalidatesCache() {
        add(alice, "# One\n\nshared word");
        assertThat(search.search(alice, q("shared")).total()).isEqualTo(1);

        add(alice, "# Two\n\nshared word again");

        var result = search.search(alice, q("shared"));
        assertThat(result.cached()).isFalse();
        assertThat(result.total()).isEqualTo(2);
    }

    @Test
    void neverReturnsOtherUsersDocuments() {
        add(alice, "# Secret\n\nquarterly budget numbers");
        var bobs = add(bob, "# Mine\n\nquarterly report");

        assertThat(ids(search.search(bob, q("quarterly")))).containsExactly(bobs);
        assertThat(search.search(bob, q("budget")).hits()).isEmpty();
    }

    @Test
    void appliesFilters() {
        var a = add(alice, "---\ntags: [java, db]\ncategory: dev\nauthor: Ann\n---\n# A\n\nsearch engine");
        add(alice, "---\ntags: [java]\ncategory: ops\nauthor: Bob\n---\n# B\n\nsearch engine");

        assertThat(search.search(alice, q("engine")).total()).isEqualTo(2);
        assertThat(ids(search.search(alice, withTags(q("engine"), List.of("java", "db"))))).containsExactly(a);
        assertThat(ids(search.search(alice, new SearchRequest("engine", null, null, null, "dev", null, null, null, null, 0, 10))))
                .containsExactly(a);
        assertThat(ids(search.search(alice, new SearchRequest("engine", null, null, null, null, "Ann", null, null, null, 0, 10))))
                .containsExactly(a);
        assertThat(search.search(alice, new SearchRequest("engine", null, null, null, null, null,
                Instant.now().plusSeconds(60), null, null, 0, 10)).hits()).isEmpty();
        assertThat(search.search(alice, new SearchRequest("engine", null, null, null, null, null,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600), null, 0, 10)).total()).isEqualTo(2);
    }

    private SearchRequest withTags(SearchRequest r, List<String> tags) {
        return new SearchRequest(r.q(), null, null, tags, null, null, null, null, null, 0, 10);
    }

    @Test
    void highlightsMatchesWithMarkAndEscapesHtml() {
        add(alice, "# Guide\n\nThe indexing pipeline handles 5 < 6 and A&B documents");

        Hit hit = search.search(alice, q("documents")).hits().get(0);

        assertThat(hit.highlights()).hasSize(1);
        assertThat(hit.highlights().get(0).field()).isEqualTo("body");
        assertThat(hit.highlights().get(0).snippet()).contains("<mark>documents</mark>").contains("5 &lt; 6").contains("A&amp;B");
    }

    @Test
    void sortsByFieldInsteadOfRelevance() {
        var small = add(alice, "# S\n\nword");
        var large = add(alice, "# L\n\nword " + "filler ".repeat(200));

        var result = search.search(alice, new SearchRequest("word", null, null, null, null, null, null, null,
                SortBy.SIZE_BYTES, 0, 10));

        assertThat(ids(result)).containsExactly(large, small);
        assertThat(result.hits().get(0).score()).isZero();
    }

    @Test
    void pagesResults() {
        for (int i = 0; i < 5; i++) {
            add(alice, "# Doc " + i + "\n\ncommon term number " + i);
        }

        var second = search.search(alice, new SearchRequest("common", null, null, null, null, null, null, null, null, 1, 2));

        assertThat(second.total()).isEqualTo(5);
        assertThat(second.hits()).hasSize(2);
        assertThat(search.search(alice, new SearchRequest("common", null, null, null, null, null, null, null, null, 2, 2))
                .hits()).hasSize(1);
    }

    @Test
    void hybridScoresAreNormalizedAndWeightsAreValidated() {
        add(alice, "# A\n\nsearch search search engine");
        add(alice, "# B\n\nsearch");

        var result = search.search(alice, new SearchRequest("search", "hybrid", Map.of("bm25", 3.0, "tfidf", 1.0),
                null, null, null, null, null, null, 0, 10));

        assertThat(result.hits()).extracting(h -> h.document().title()).containsExactly("A", "B");
        assertThat(result.hits()).allSatisfy(h -> assertThat(h.score()).isBetween(0f, 1f));
        assertThatThrownBy(() -> search.search(alice, new SearchRequest("search", "hybrid", Map.of("bm25", -1.0),
                null, null, null, null, null, null, 0, 10))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBadRequestsAndIgnoresStopWordOnlyQueries() {
        add(alice, "# A\n\nsomething here");

        assertThat(search.search(alice, q("the and")).hits()).isEmpty();
        assertThatThrownBy(() -> search.search(alice, q("  "))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> search.search(alice, new SearchRequest("x", "magic", null, null, null, null, null, null,
                null, 0, 10))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> search.search(alice, new SearchRequest("x", null, null, null, null, null, null, null,
                null, 0, 1000))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dropsDocumentsDeletedAfterTheLastIndexCommit() {
        var id = add(alice, "# A\n\nephemeral text");

        documents.delete(alice, id); // index still has it until the worker runs

        assertThat(search.search(alice, q("ephemeral")).hits()).isEmpty();
    }
}
