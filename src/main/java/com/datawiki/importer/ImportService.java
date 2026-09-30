package com.datawiki.importer;

import com.datawiki.documents.DocumentService;
import com.datawiki.markdown.InvalidDocumentException;
import com.datawiki.markdown.MarkdownParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Imports {@code .md} files, also from {@code .zip} archives. Each file is stored on its own, so a bad file
 * never blocks the others; the report says what happened to every one.
 */
@Service
public class ImportService {

    public enum Status { created, duplicate, rejected, skipped }

    public record FileReport(String path, Status status, UUID documentId, String reason, List<String> warnings) {
    }

    public record Report(List<FileReport> files, int created, int duplicates, int rejected) {
    }

    // ponytail: entry count is capped, total unpacked size is not; add a byte budget if archives become untrusted
    private static final int MAX_ARCHIVE_ENTRIES = 1000;

    private final DocumentService documents;
    private final MarkdownParser parser;
    private final long maxFileBytes;

    public ImportService(DocumentService documents, MarkdownParser parser,
                         @Value("${app.import.max-file-bytes}") long maxFileBytes) {
        this.documents = documents;
        this.parser = parser;
        this.maxFileBytes = maxFileBytes;
    }

    public Report importFiles(UUID ownerId, List<MultipartFile> files) throws IOException {
        List<FileReport> reports = new ArrayList<>();
        for (MultipartFile file : files) {
            String name = file.getOriginalFilename() == null ? "unnamed" : file.getOriginalFilename();
            if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                try (var zip = new ZipInputStream(file.getInputStream())) {
                    readArchive(ownerId, name, zip, reports);
                }
            } else {
                reports.add(store(ownerId, name, file.getBytes()));
            }
        }
        return new Report(reports, count(reports, Status.created), count(reports, Status.duplicate),
                count(reports, Status.rejected));
    }

    private void readArchive(UUID ownerId, String archive, ZipInputStream zip, List<FileReport> reports)
            throws IOException {
        int entries = 0;
        for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
            if (entry.isDirectory()) {
                continue;
            }
            String path = archive + "!/" + entry.getName();
            if (++entries > MAX_ARCHIVE_ENTRIES) {
                reports.add(skipped(path, "Archive has more than " + MAX_ARCHIVE_ENTRIES + " files"));
                return;
            }
            if (!isMarkdown(entry.getName())) {
                reports.add(skipped(path, "Not a Markdown file"));
                continue;
            }
            // one byte past the limit is enough for the parser to reject the entry as too large
            byte[] bytes = zip.readNBytes((int) Math.min(maxFileBytes + 1, Integer.MAX_VALUE));
            reports.add(store(ownerId, path, bytes));
        }
    }

    private FileReport store(UUID ownerId, String path, byte[] bytes) {
        if (!isMarkdown(path)) {
            return skipped(path, "Not a Markdown file");
        }
        try {
            var parsed = parser.parse(bytes, path);
            var saved = documents.create(ownerId, parsed);
            var status = saved.outcome() == DocumentService.Outcome.CREATED ? Status.created : Status.duplicate;
            return new FileReport(path, status, saved.id(), null, parsed.warnings());
        } catch (InvalidDocumentException e) {
            return new FileReport(path, Status.rejected, null, e.getMessage(), List.of());
        }
    }

    private static FileReport skipped(String path, String reason) {
        return new FileReport(path, Status.skipped, null, reason, List.of());
    }

    private static int count(List<FileReport> reports, Status status) {
        return (int) reports.stream().filter(r -> r.status() == status).count();
    }

    private static boolean isMarkdown(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md") || lower.endsWith(".markdown");
    }
}
