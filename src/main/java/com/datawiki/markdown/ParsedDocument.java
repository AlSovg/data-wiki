package com.datawiki.markdown;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Result of parsing one Markdown file.
 *
 * @param content       normalized full text (frontmatter block kept, inline HTML removed); this is what gets stored
 * @param contentHash   SHA-256 hex of {@code content}; duplicates are detected by it
 * @param body          prose text without headings and code (index field {@code body})
 * @param headings      heading texts, one per line (index field {@code headings})
 * @param code          code block contents (index field {@code code})
 * @param author        from frontmatter only; the caller falls back to the user name
 * @param date          frontmatter {@code date}, if present and recognized
 * @param extra         frontmatter keys other than title/tags/category/author/date
 * @param wordCount     words in body and headings (code excluded)
 * @param charCount     length of {@code content} in chars
 * @param warnings      non-fatal problems, e.g. invalid frontmatter
 */
public record ParsedDocument(
        String title,
        List<String> tags,
        String category,
        String author,
        Instant date,
        Map<String, Object> extra,
        String content,
        String contentHash,
        List<Heading> structure,
        String body,
        String headings,
        String code,
        List<String> codeLanguages,
        int wordCount,
        int charCount,
        long sizeBytes,
        int codeBlockCount,
        int linkCount,
        int readingMinutes,
        List<String> warnings) {
}
