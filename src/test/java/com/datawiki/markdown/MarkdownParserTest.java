package com.datawiki.markdown;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.datawiki.markdown.InvalidDocumentException.Reason;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class MarkdownParserTest {

    private final MarkdownParser parser = new MarkdownParser(1_048_576);

    private ParsedDocument parse(String text) {
        return parser.parse(text, "note.md");
    }

    @Test
    void readsFrontmatterMetadata() {
        ParsedDocument d = parse("""
                ---
                title: How BM25 works
                tags: [Search, ranking, search, " "]
                category: algorithms
                author: Alexander
                date: 2024-03-05
                lang: ru
                ---
                # Ignored heading
                Text.
                """);

        assertThat(d.title()).isEqualTo("How BM25 works");
        assertThat(d.tags()).containsExactly("search", "ranking");
        assertThat(d.category()).isEqualTo("algorithms");
        assertThat(d.author()).isEqualTo("Alexander");
        assertThat(d.date()).isEqualTo(Instant.parse("2024-03-05T00:00:00Z"));
        assertThat(d.extra()).containsOnlyKeys("lang");
        assertThat(d.warnings()).isEmpty();
        assertThat(d.content()).startsWith("---\ntitle: How BM25 works");
        assertThat(d.body()).isEqualTo("Text.");
    }

    @Test
    void tagsCanBeCommaSeparatedAndCyrillicIsLowercased() {
        ParsedDocument d = parse("---\ntags: Поиск, Индекс\n---\ntext");

        assertThat(d.tags()).containsExactly("поиск", "индекс");
    }

    @Test
    void capsTagsAtTwentyWithWarning() {
        String tags = String.join(",", IntStream.range(0, 25).mapToObj(i -> "t" + i).toList());

        ParsedDocument d = parse("---\ntags: [" + tags + "]\n---\ntext");

        assertThat(d.tags()).hasSize(MarkdownParser.MAX_TAGS);
        assertThat(d.warnings()).hasSize(1);
    }

    @Test
    void titleFallsBackToFirstH1ThenFileNameThenUntitled() {
        assertThat(parser.parse("intro\n\n# Real title\n## Sub", "dir/file.md").title()).isEqualTo("Real title");
        assertThat(parser.parse("just text", "notes/My Doc.md").title()).isEqualTo("My Doc");
        assertThat(parser.parse("just text", "C:\\x\\Win Note.MD").title()).isEqualTo("Win Note");
        assertThat(parser.parse("just text", null).title()).isEqualTo("Untitled");
    }

    @Test
    void invalidYamlDoesNotFailImport() {
        ParsedDocument d = parser.parse("---\ntitle: [unclosed\n: :\n---\n# Heading\nBody text", "a.md");

        assertThat(d.warnings()).hasSize(1).first().asString().contains("Invalid frontmatter");
        assertThat(d.tags()).isEmpty();
        assertThat(d.title()).isEqualTo("Heading");
        assertThat(d.body()).isEqualTo("Body text");
        assertThat(d.content()).startsWith("---\ntitle: [unclosed");
    }

    @Test
    void frontmatterThatIsNotAMappingIsIgnoredWithWarning() {
        ParsedDocument d = parse("---\njust a sentence\n---\ntext");

        assertThat(d.warnings()).hasSize(1);
        assertThat(d.extra()).isEmpty();
    }

    @Test
    void unclosedFrontmatterIsPlainContent() {
        ParsedDocument d = parse("---\ntitle: x\n\nno closing marker");

        assertThat(d.title()).isEqualTo("note");
        assertThat(d.warnings()).isEmpty();
    }

    @Test
    void buildsHeadingTree() {
        ParsedDocument d = parse("""
                # A
                ## B
                ### C
                ## D
                # E
                ### F
                """);

        assertThat(d.structure()).hasSize(2);
        Heading a = d.structure().get(0);
        assertThat(a.text()).isEqualTo("A");
        assertThat(a.children()).extracting(Heading::text).containsExactly("B", "D");
        assertThat(a.children().get(0).children()).extracting(Heading::text).containsExactly("C");
        assertThat(d.structure().get(1).children()).extracting(Heading::text).containsExactly("F");
        assertThat(d.headings()).isEqualTo("A\nB\nC\nD\nE\nF");
        assertThat(d.body()).isEmpty();
    }

    @Test
    void separatesCodeFromProseAndKeepsLanguage() {
        ParsedDocument d = parse("""
                Use `map` here.

                ```Java
                int secret = 1;
                ```

                    indented code

                After.
                """);

        assertThat(d.body()).contains("Use map here.").contains("After.").doesNotContain("secret", "indented");
        assertThat(d.code()).contains("int secret = 1;").contains("indented code");
        assertThat(d.codeLanguages()).containsExactly("java");
        assertThat(d.codeBlockCount()).isEqualTo(2);
    }

    @Test
    void removesInlineHtmlButKeepsHtmlInsideCode() {
        ParsedDocument d = parse("""
                Hello <b>world</b> and <script>alert(1)</script>.

                <div class="x">
                hidden block
                </div>

                <!-- comment -->

                ```html
                <p>kept</p>
                ```
                """);

        assertThat(d.content()).doesNotContain("<b>", "</b>", "<script>", "<div", "hidden block", "comment")
                .contains("Hello world and alert(1).")
                .contains("<p>kept</p>");
        assertThat(d.body()).isEqualTo("Hello world and alert(1).");
    }

    @Test
    void indexesTablesListsAndFormatting() {
        ParsedDocument d = parse("""
                | Name | Score |
                |------|-------|
                | foo  | bar   |

                - [x] done ~~old~~ **new**
                - [ ] [link text](http://example.com)
                """);

        assertThat(d.body()).contains("Name Score").contains("foo bar").contains("done old new").contains("link text")
                .doesNotContain("http://example.com", "|", "[x]");
        assertThat(d.linkCount()).isEqualTo(1);
    }

    @Test
    void computesMetrics() {
        String words = String.join(" ", IntStream.range(0, 450).mapToObj(i -> "слово").toList());

        ParsedDocument d = parse("# Title\n\n" + words);

        assertThat(d.wordCount()).isEqualTo(451);
        assertThat(d.readingMinutes()).isEqualTo(3);
        assertThat(d.charCount()).isEqualTo(d.content().length());
        assertThat(d.sizeBytes()).isEqualTo(d.content().getBytes(UTF_8).length);
    }

    @Test
    void normalizesLineEndingsBomAndUnicode() {
        ParsedDocument crlf = parse("\uFEFF# T\r\n\r\ncaf\u0065\u0301\r\n");
        ParsedDocument lf = parse("# T\n\ncaf\u00e9");

        assertThat(crlf.content()).isEqualTo("# T\n\ncaf\u00e9");
        assertThat(crlf.contentHash()).isEqualTo(lf.contentHash());
    }

    @Test
    void reparsingNormalizedContentIsStable() {
        ParsedDocument first = parse("---\ntags: [a]\n---\n# T\n\nText <i>x</i>\n\n<div>y</div>\n");

        ParsedDocument second = parse(first.content());

        assertThat(second.content()).isEqualTo(first.content());
        assertThat(second.contentHash()).isEqualTo(first.contentHash());
    }

    @Test
    void differentContentGivesDifferentHash() {
        assertThat(parse("one").contentHash()).isNotEqualTo(parse("two").contentHash());
    }

    @Test
    void rejectsTooLargeBinaryAndEmptyInput() {
        MarkdownParser small = new MarkdownParser(10);

        assertThatThrownBy(() -> small.parse("x".repeat(11).getBytes(UTF_8), "a.md")).isInstanceOfSatisfying(
                InvalidDocumentException.class, e -> assertThat(e.reason()).isEqualTo(Reason.TOO_LARGE));
        assertThatThrownBy(() -> small.parse("я".repeat(6), "a.md")).isInstanceOfSatisfying(
                InvalidDocumentException.class, e -> assertThat(e.reason()).isEqualTo(Reason.TOO_LARGE));
        assertReason(new byte[] {'a', 0, 'b'}, Reason.NOT_TEXT);
        assertReason(new byte[] {(byte) 0xFF, (byte) 0xFE, 'a'}, Reason.NOT_TEXT);
        assertReason("  \r\n\t ".getBytes(UTF_8), Reason.EMPTY);
    }

    private void assertReason(byte[] bytes, Reason reason) {
        assertThatThrownBy(() -> parser.parse(bytes, "a.md")).isInstanceOfSatisfying(
                InvalidDocumentException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }

    @Test
    void acceptsDocumentWithOnlyFrontmatter() {
        ParsedDocument d = parser.parse("---\ntitle: Only meta\n---".getBytes(UTF_8), "a.md");

        assertThat(d.title()).isEqualTo("Only meta");
        assertThat(d.body()).isEmpty();
        assertThat(d.wordCount()).isZero();
        assertThat(d.tags()).isEqualTo(List.of());
    }
}
