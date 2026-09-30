package com.datawiki.markdown;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.CustomNode;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parses, validates and normalizes a Markdown document (CommonMark + GFM tables, strikethrough, task lists,
 * YAML frontmatter). Stateless and thread-safe.
 */
@Component
public class MarkdownParser {

    static final int MAX_TAGS = 20;
    static final int MAX_TITLE = 255;
    private static final int WORDS_PER_MINUTE = 200;
    private static final Set<String> KNOWN_KEYS = Set.of("title", "tags", "category", "author", "date");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern FRONTMATTER =
            Pattern.compile("\\A---[ \\t]*\\n(?:(.*?)\\n)?(?:---|\\.\\.\\.)[ \\t]*(?:\\n|\\z)", Pattern.DOTALL);
    private static final Pattern FILE_EXTENSION = Pattern.compile("(?i)\\.(md|markdown)$");

    private final long maxBytes;
    private final Parser parser = Parser.builder()
            .extensions(List.of(TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create()))
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build();

    public MarkdownParser(@Value("${app.import.max-file-bytes}") long maxBytes) {
        this.maxBytes = maxBytes;
    }

    /** Parses an uploaded file: checks size and UTF-8, rejects binary content. */
    public ParsedDocument parse(byte[] bytes, String fileName) {
        if (bytes.length > maxBytes) {
            throw tooLarge();
        }
        String text;
        try {
            text = UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new InvalidDocumentException(InvalidDocumentException.Reason.NOT_TEXT, "File is not valid UTF-8");
        }
        return build(text, fileName);
    }

    /** Parses text submitted through the API (document edit). */
    public ParsedDocument parse(String text, String fileName) {
        if (text.getBytes(UTF_8).length > maxBytes) {
            throw tooLarge();
        }
        return build(text, fileName);
    }

    private InvalidDocumentException tooLarge() {
        return new InvalidDocumentException(InvalidDocumentException.Reason.TOO_LARGE,
                "Document is larger than " + maxBytes + " bytes");
    }

    private ParsedDocument build(String raw, String fileName) {
        if (raw.indexOf('\0') >= 0) {
            throw new InvalidDocumentException(InvalidDocumentException.Reason.NOT_TEXT, "File contains binary data");
        }
        String text = normalize(raw);
        if (text.isEmpty()) {
            throw new InvalidDocumentException(InvalidDocumentException.Reason.EMPTY, "Document is empty");
        }

        List<String> warnings = new ArrayList<>();
        String frontmatterBlock = "";
        String body = text;
        Map<String, Object> meta = Map.of();
        Matcher fm = FRONTMATTER.matcher(text);
        if (fm.find()) {
            frontmatterBlock = text.substring(0, fm.end());
            body = text.substring(fm.end());
            meta = parseYaml(fm.group(1), warnings);
        }

        Collector doc = new Collector();
        parser.parse(body).accept(doc);

        String content = (frontmatterBlock + removeRanges(body, doc.htmlSpans)).strip();
        String bodyText = doc.body.toString().strip();
        String headingsText = doc.headings.toString().strip();
        int words = countWords(bodyText) + countWords(headingsText);

        String title = clip(str(meta.get("title")), "title", warnings);
        if (title == null) {
            title = clip(doc.firstH1, "title", warnings);
        }
        if (title == null) {
            title = fileTitle(fileName);
        }

        Map<String, Object> extra = new LinkedHashMap<>(meta);
        extra.keySet().removeAll(KNOWN_KEYS);

        return new ParsedDocument(
                title,
                tags(meta.get("tags"), warnings),
                str(meta.get("category")),
                str(meta.get("author")),
                date(meta.get("date"), warnings),
                extra,
                content,
                sha256(content),
                doc.roots,
                bodyText,
                headingsText,
                doc.code.toString().strip(),
                List.copyOf(doc.languages),
                words,
                content.length(),
                content.getBytes(UTF_8).length,
                doc.codeBlocks,
                doc.links,
                words == 0 ? 0 : (words + WORDS_PER_MINUTE - 1) / WORDS_PER_MINUTE,
                warnings);
    }

    private static String normalize(String raw) {
        String s = raw.startsWith("﻿") ? raw.substring(1) : raw;
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        return Normalizer.normalize(s, Normalizer.Form.NFC).strip();
    }

    private static Map<String, Object> parseYaml(String yaml, List<String> warnings) {
        if (yaml == null || yaml.isBlank()) {
            return Map.of();
        }
        try {
            LoaderOptions options = new LoaderOptions();
            options.setMaxAliasesForCollections(10);
            options.setNestingDepthLimit(20);
            Object parsed = new Yaml(new SafeConstructor(options)).load(yaml);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((k, v) -> result.put(String.valueOf(k), v));
                return result;
            }
            if (parsed != null) {
                warnings.add("Frontmatter is not a key-value mapping, metadata ignored");
            }
        } catch (RuntimeException e) {
            String reason = String.valueOf(e.getMessage()).lines().findFirst().orElse("");
            warnings.add("Invalid frontmatter YAML, metadata ignored: " + reason);
        }
        return Map.of();
    }

    private static List<String> tags(Object value, List<String> warnings) {
        Collection<?> items = switch (value) {
            case null -> List.of();
            case Collection<?> c -> c;
            case String s -> List.of(s.split(","));
            default -> {
                warnings.add("Frontmatter 'tags' must be a list or a comma-separated string, ignored");
                yield List.of();
            }
        };
        List<String> tags = items.stream()
                .filter(t -> t instanceof String || t instanceof Number || t instanceof Boolean)
                .map(t -> t.toString().strip().toLowerCase(Locale.ROOT))
                .filter(t -> !t.isEmpty())
                .distinct()
                .toList();
        if (tags.size() > MAX_TAGS) {
            warnings.add("More than " + MAX_TAGS + " tags, the rest are dropped");
            return tags.subList(0, MAX_TAGS);
        }
        return tags;
    }

    private static Instant date(Object value, List<String> warnings) {
        switch (value) {
            case null -> {
                return null;
            }
            case Date d -> {
                return d.toInstant();
            }
            case String s -> {
                Instant parsed = parseDate(s.strip());
                if (parsed != null) {
                    return parsed;
                }
            }
            default -> { }
        }
        warnings.add("Unrecognized frontmatter 'date', ignored");
        return null;
    }

    private static Instant parseDate(String s) {
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException ignored) { }
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException ignored) { }
        try {
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) { }
        try {
            return LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) { }
        return null;
    }

    private static String str(Object value) {
        if (value == null || value instanceof Collection || value instanceof Map) {
            return null;
        }
        String s = value.toString().strip();
        return s.isEmpty() ? null : s;
    }

    private static String clip(String title, String field, List<String> warnings) {
        if (title != null && title.length() > MAX_TITLE) {
            warnings.add("Frontmatter '" + field + "' is longer than " + MAX_TITLE + " chars, truncated");
            return title.substring(0, MAX_TITLE);
        }
        return title;
    }

    private static String fileTitle(String fileName) {
        if (fileName != null) {
            String name = fileName.substring(Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\')) + 1);
            name = FILE_EXTENSION.matcher(name).replaceFirst("").strip();
            if (!name.isEmpty()) {
                return name.length() > MAX_TITLE ? name.substring(0, MAX_TITLE) : name;
            }
        }
        return "Untitled";
    }

    private static int countWords(String text) {
        int n = 0;
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** Removes [start, start+length) ranges; later ranges are cut first so earlier offsets stay valid. */
    private static String removeRanges(String s, List<int[]> ranges) {
        if (ranges.isEmpty()) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s);
        int limit = s.length();
        for (int[] r : ranges.stream().sorted(Comparator.comparingInt((int[] r) -> r[0]).reversed()).toList()) {
            int end = Math.min(r[0] + r[1], limit);
            if (r[0] < end) {
                sb.delete(r[0], end);
                limit = r[0];
            }
        }
        return sb.toString();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Walks the AST once: prose, headings tree, code blocks, links and the source ranges of inline HTML. */
    private static final class Collector extends AbstractVisitor {
        final StringBuilder body = new StringBuilder();
        final StringBuilder headings = new StringBuilder();
        final StringBuilder code = new StringBuilder();
        final List<Heading> roots = new ArrayList<>();
        final Deque<Heading> stack = new ArrayDeque<>();
        final Set<String> languages = new LinkedHashSet<>();
        final List<int[]> htmlSpans = new ArrayList<>();
        String firstH1;
        int codeBlocks;
        int links;

        @Override
        public void visit(org.commonmark.node.Heading heading) {
            Collector inner = new Collector();
            inner.visitChildren(heading);
            links += inner.links;
            htmlSpans.addAll(inner.htmlSpans);
            String text = inner.body.toString().strip();
            if (text.isEmpty()) {
                return;
            }
            Heading node = new Heading(heading.getLevel(), text, new ArrayList<>());
            while (!stack.isEmpty() && stack.peek().level() >= node.level()) {
                stack.pop();
            }
            (stack.isEmpty() ? roots : stack.peek().children()).add(node);
            stack.push(node);
            headings.append(text).append('\n');
            if (firstH1 == null && node.level() == 1) {
                firstH1 = text;
            }
        }

        @Override
        public void visit(Paragraph paragraph) {
            visitChildren(paragraph);
            body.append('\n');
        }

        @Override
        public void visit(Text text) {
            body.append(text.getLiteral());
        }

        @Override
        public void visit(Code inlineCode) {
            body.append(inlineCode.getLiteral());
        }

        @Override
        public void visit(SoftLineBreak softLineBreak) {
            body.append(' ');
        }

        @Override
        public void visit(HardLineBreak hardLineBreak) {
            body.append(' ');
        }

        @Override
        public void visit(FencedCodeBlock block) {
            addCode(block.getLiteral());
            String language = block.getInfo() == null ? "" : block.getInfo().strip().split("\\s+")[0];
            if (!language.isEmpty()) {
                languages.add(language.toLowerCase(Locale.ROOT));
            }
        }

        @Override
        public void visit(IndentedCodeBlock block) {
            addCode(block.getLiteral());
        }

        @Override
        public void visit(HtmlBlock block) {
            addSpans(block);
        }

        @Override
        public void visit(HtmlInline html) {
            addSpans(html);
        }

        @Override
        public void visit(Link link) {
            links++;
            visitChildren(link);
        }

        @Override
        public void visit(CustomNode node) {
            visitChildren(node);
            if (node instanceof TableCell) {
                body.append(' ');
            } else if (node instanceof TableRow) {
                body.append('\n');
            }
        }

        private void addCode(String literal) {
            codeBlocks++;
            code.append(literal);
            if (!literal.endsWith("\n")) {
                code.append('\n');
            }
        }

        private void addSpans(Node node) {
            for (SourceSpan span : node.getSourceSpans()) {
                htmlSpans.add(new int[] {span.getInputIndex(), span.getLength()});
            }
        }
    }
}
