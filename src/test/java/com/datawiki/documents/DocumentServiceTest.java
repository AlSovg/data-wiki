package com.datawiki.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datawiki.documents.DocumentService.DuplicateContentException;
import com.datawiki.documents.DocumentService.NotFoundException;
import com.datawiki.documents.DocumentService.Outcome;
import com.datawiki.markdown.MarkdownParser;
import java.util.UUID;
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
class DocumentServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    DocumentService documents;
    @Autowired
    MarkdownParser parser;
    @Autowired
    JdbcClient jdbc;

    UUID alice;
    UUID bob;

    @BeforeEach
    void users() {
        jdbc.sql("truncate users, tags cascade").update();
        alice = user("alice@example.com");
        bob = user("bob@example.com");
    }

    private UUID user(String email) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into users (id, email, password_hash) values (:id, :email, 'x')")
                .param("id", id).param("email", email).update();
        return id;
    }

    private com.datawiki.markdown.ParsedDocument md(String text) {
        return parser.parse(text, "note.md");
    }

    @Test
    void createStoresDocumentWithTagsAndFallbackAuthor() {
        var result = documents.create(alice, md("---\ntitle: Hello\ntags: [b, a]\ncategory: dev\n---\n# Hello\n\ntext"));

        assertThat(result.outcome()).isEqualTo(Outcome.CREATED);
        var doc = documents.find(alice, result.id()).orElseThrow();
        assertThat(doc.meta().title()).isEqualTo("Hello");
        assertThat(doc.meta().tags()).containsExactly("a", "b");
        assertThat(doc.meta().category()).isEqualTo("dev");
        assertThat(doc.meta().author()).isEqualTo("alice@example.com");
        assertThat(doc.meta().version()).isEqualTo(1);
        assertThat(doc.content()).contains("# Hello");
    }

    @Test
    void sameContentIsDuplicateForOwnerButNotForOthers() {
        String text = "# Same\n\nbody";
        var first = documents.create(alice, md(text));
        var again = documents.create(alice, md(text));
        var other = documents.create(bob, md(text));

        assertThat(again.outcome()).isEqualTo(Outcome.DUPLICATE);
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(other.outcome()).isEqualTo(Outcome.CREATED);
    }

    @Test
    void updateCreatesNewVersionAndKeepsOldOne() {
        var id = documents.create(alice, md("---\ntags: [x]\n---\n# One\n\nfirst")).id();

        assertThat(documents.update(alice, id, md("---\ntags: [y]\n---\n# One\n\nsecond")).outcome())
                .isEqualTo(Outcome.UPDATED);

        var doc = documents.find(alice, id).orElseThrow();
        assertThat(doc.meta().version()).isEqualTo(2);
        assertThat(doc.meta().tags()).containsExactly("y");
        assertThat(doc.content()).contains("second");
        assertThat(documents.versions(alice, id)).extracting(DocumentService.VersionSummary::version)
                .containsExactly(2, 1);
        assertThat(documents.version(alice, id, 1).orElseThrow().content()).contains("first");
    }

    @Test
    void updateWithSameContentIsUnchanged() {
        var id = documents.create(alice, md("# One\n\nfirst")).id();

        assertThat(documents.update(alice, id, md("# One\n\nfirst")).outcome()).isEqualTo(Outcome.UNCHANGED);
        assertThat(documents.versions(alice, id)).hasSize(1);
    }

    @Test
    void updateToContentOfAnotherDocumentIsRejected() {
        documents.create(alice, md("# A\n\naaa"));
        var b = documents.create(alice, md("# B\n\nbbb")).id();

        assertThatThrownBy(() -> documents.update(alice, b, md("# A\n\naaa")))
                .isInstanceOf(DuplicateContentException.class);
        assertThat(documents.find(alice, b).orElseThrow().meta().version()).isEqualTo(1);
    }

    @Test
    void otherUserCannotSeeChangeOrDelete() {
        var id = documents.create(alice, md("# Mine\n\nsecret")).id();

        assertThat(documents.find(bob, id)).isEmpty();
        assertThat(documents.versions(bob, id)).isEmpty();
        assertThat(documents.list(bob, 0, 10).total()).isZero();
        assertThatThrownBy(() -> documents.update(bob, id, md("# Mine\n\nhacked"))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> documents.delete(bob, id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteHidesDocumentAndFreesContentForReimport() {
        String text = "# Gone\n\nbody";
        var id = documents.create(alice, md(text)).id();

        documents.delete(alice, id);

        assertThat(documents.find(alice, id)).isEmpty();
        assertThat(documents.list(alice, 0, 10).items()).isEmpty();
        assertThat(documents.create(alice, md(text)).outcome()).isEqualTo(Outcome.CREATED);
    }

    @Test
    void listIsPagedNewestFirst() {
        for (int i = 0; i < 3; i++) {
            documents.create(alice, md("# Doc " + i + "\n\nbody " + i));
        }

        var page = documents.list(alice, 0, 2);

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).extracting(DocumentService.DocumentMeta::title).containsExactly("Doc 2", "Doc 1");
    }
}
