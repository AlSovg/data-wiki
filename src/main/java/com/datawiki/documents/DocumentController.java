package com.datawiki.documents;

import static com.datawiki.auth.CurrentUser.id;

import com.datawiki.documents.DocumentService.DocumentMeta;
import com.datawiki.documents.DocumentService.ListFilter;
import com.datawiki.documents.DocumentService.NotFoundException;
import com.datawiki.documents.DocumentService.Page;
import com.datawiki.documents.DocumentService.StoredDocument;
import com.datawiki.documents.DocumentService.StoredVersion;
import com.datawiki.documents.DocumentService.VersionSummary;
import com.datawiki.markdown.Heading;
import com.datawiki.markdown.MarkdownParser;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents")
class DocumentController {

    record DocumentView(@JsonUnwrapped DocumentMeta meta, String content, Map<String, Object> extra,
                        List<Heading> structure) {
    }

    record VersionView(@JsonUnwrapped VersionSummary summary, String content, Map<String, Object> extra) {
    }

    record DocumentUpdate(@NotNull String content) {
    }

    private final DocumentService documents;
    private final MarkdownParser parser;

    DocumentController(DocumentService documents, MarkdownParser parser) {
        this.documents = documents;
        this.parser = parser;
    }

    @GetMapping
    Page list(Principal user,
              @RequestParam(required = false) List<String> tags,
              @RequestParam(required = false) String category,
              @RequestParam(required = false) String author,
              @RequestParam(name = "updated_from", required = false)
              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant updatedFrom,
              @RequestParam(name = "updated_to", required = false)
              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant updatedTo,
              @RequestParam(defaultValue = "updated_at") String sort,
              @RequestParam(defaultValue = "desc") String order,
              @RequestParam(defaultValue = "0") int page,
              @RequestParam(defaultValue = "20") int size) {
        if (!order.equals("asc") && !order.equals("desc")) {
            throw new IllegalArgumentException("order must be asc or desc");
        }
        var filter = new ListFilter(tags, category, author, updatedFrom, updatedTo, sort, order.equals("asc"));
        return documents.list(id(user), filter, page, size);
    }

    @GetMapping("/{id}")
    DocumentView get(Principal user, @PathVariable UUID id) {
        return view(documents.find(id(user), id).orElseThrow(() -> new NotFoundException(id)));
    }

    /** A new version, or the current one when the normalized content is unchanged. */
    @PutMapping("/{id}")
    DocumentView update(Principal user, @PathVariable UUID id, @Valid @RequestBody DocumentUpdate body) {
        UUID owner = id(user);
        StoredDocument current = documents.find(owner, id).orElseThrow(() -> new NotFoundException(id));
        documents.update(owner, id, parser.parse(body.content(), current.meta().title()));
        return view(documents.find(owner, id).orElseThrow(() -> new NotFoundException(id)));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(Principal user, @PathVariable UUID id) {
        documents.delete(id(user), id);
    }

    @GetMapping("/{id}/versions")
    List<VersionSummary> versions(Principal user, @PathVariable UUID id) {
        documents.find(id(user), id).orElseThrow(() -> new NotFoundException(id));
        return documents.versions(id(user), id);
    }

    @GetMapping("/{id}/versions/{version}")
    VersionView version(Principal user, @PathVariable UUID id, @PathVariable int version) {
        StoredVersion v = documents.version(id(user), id, version).orElseThrow(() -> new NotFoundException(id));
        return new VersionView(v.summary(), v.content(), v.extra());
    }

    private DocumentView view(StoredDocument doc) {
        return new DocumentView(doc.meta(), doc.content(), doc.extra(),
                parser.parse(doc.content(), doc.meta().title()).structure());
    }
}
