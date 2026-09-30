package com.datawiki.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import com.datawiki.documents.DocumentService;
import com.datawiki.markdown.MarkdownParser;
import java.util.UUID;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
class IndexWorkerTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

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

    UUID owner;

    @BeforeEach
    void reset() {
        jdbc.sql("truncate users, tags cascade").update();
        index.clear();
        index.commit();
        owner = UUID.randomUUID();
        jdbc.sql("insert into users (id, email, password_hash) values (:id, 'a@example.com', 'x')")
                .param("id", owner).update();
    }

    private UUID create(String markdown) {
        return documents.create(owner, parser.parse(markdown, "note.md")).id();
    }

    private boolean indexed(UUID id, String bodyTerm) {
        return index.search(s -> s.search(new TermQuery(new Term(LuceneIndex.BODY, bodyTerm)), 10).scoreDocs.length > 0
                && s.storedFields().document(s.search(new TermQuery(new Term(LuceneIndex.BODY, bodyTerm)), 10)
                .scoreDocs[0].doc).get(LuceneIndex.ID).equals(id.toString()));
    }

    private int pendingTasks() {
        return jdbc.sql("select count(*) from index_tasks").query(Integer.class).single();
    }

    @Test
    void createUpdateDeleteReachTheIndex() {
        var id = create("# Doc\n\nalpha");
        assertThat(indexed(id, "alpha")).isFalse();

        worker.processPending();
        assertThat(indexed(id, "alpha")).isTrue();
        assertThat(pendingTasks()).isZero();

        documents.update(owner, id, parser.parse("# Doc\n\nbeta", "note.md"));
        worker.processPending();
        assertThat(indexed(id, "beta")).isTrue();
        assertThat(indexed(id, "alpha")).isFalse();

        documents.delete(owner, id);
        worker.processPending();
        assertThat(index.size()).isZero();
        assertThat(pendingTasks()).isZero();
    }

    @Test
    void duplicateAndUnchangedDoNotQueueWork() {
        var id = create("# Doc\n\nalpha");
        worker.processPending();

        create("# Doc\n\nalpha");
        documents.update(owner, id, parser.parse("# Doc\n\nalpha", "note.md"));

        assertThat(pendingTasks()).isZero();
    }

    @Test
    void manyChangesToOneDocumentCollapseIntoOneIndexEntry() {
        var id = create("# Doc\n\none");
        documents.update(owner, id, parser.parse("# Doc\n\ntwo", "note.md"));
        documents.update(owner, id, parser.parse("# Doc\n\nthree", "note.md"));

        worker.processPending();

        assertThat(index.size()).isEqualTo(1);
        assertThat(indexed(id, "three")).isTrue();
        assertThat(pendingTasks()).isZero();
    }

    @Test
    void failingDocumentIsRetriedUpToTheLimitAndDoesNotBlockOthers() {
        var bad = create("# Bad\n\nbroken");
        var good = create("# Good\n\nfine");
        // stored content that no longer passes the parser (here: over the size limit)
        jdbc.sql("update document_versions set content = repeat('a', 1100000) where document_id = :id")
                .param("id", bad).update();

        worker.processPending();

        assertThat(indexed(good, "fine")).isTrue();
        assertThat(jdbc.sql("select attempts from index_tasks where document_id = :id").param("id", bad)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select last_error from index_tasks where document_id = :id").param("id", bad)
                .query(String.class).single()).contains("larger than");

        for (int i = 0; i < 10; i++) {
            worker.processPending();
        }
        assertThat(jdbc.sql("select attempts from index_tasks where document_id = :id").param("id", bad)
                .query(Integer.class).single()).isEqualTo(5);
        assertThat(worker.processPending()).isZero();
    }

    @Test
    void rebuildRestoresTheIndexFromPostgres() {
        var a = create("# A\n\nalpha");
        var b = create("# B\n\nbeta");
        var gone = create("# C\n\ngamma");
        worker.processPending();
        documents.delete(owner, gone);
        worker.processPending();
        index.clear();
        index.commit();
        assertThat(index.size()).isZero();

        worker.rebuild();
        worker.processPending();

        assertThat(index.size()).isEqualTo(2);
        assertThat(indexed(a, "alpha")).isTrue();
        assertThat(indexed(b, "beta")).isTrue();
    }
}
