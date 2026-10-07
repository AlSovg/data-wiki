package com.datawiki.documents;

import static com.datawiki.auth.CurrentUser.id;

import com.datawiki.documents.DocumentController.DocumentUpdate;
import com.datawiki.documents.DocumentController.DocumentView;
import com.datawiki.documents.DocumentService.NotFoundException;
import com.datawiki.documents.DocumentService.Permission;
import com.datawiki.documents.DocumentService.SharedAccess;
import com.datawiki.documents.DocumentService.StoredDocument;
import com.datawiki.markdown.MarkdownParser;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.validation.Valid;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * A document opened by share link: any signed-in user with the token. Shared documents stay out of the
 * recipient's search and list; edits are versions authored by the recipient.
 */
@RestController
@RequestMapping("/api/shared/{token}")
class SharedController {

    record SharedView(@JsonUnwrapped DocumentView document, Permission permission) {
    }

    private final DocumentService documents;
    private final MarkdownParser parser;

    SharedController(DocumentService documents, MarkdownParser parser) {
        this.documents = documents;
        this.parser = parser;
    }

    @GetMapping
    SharedView get(@PathVariable String token) {
        SharedAccess access = resolve(token);
        return new SharedView(DocumentController.view(parser, load(access)), access.permission());
    }

    @PutMapping
    SharedView update(Principal user, @PathVariable String token, @Valid @RequestBody DocumentUpdate body) {
        SharedAccess access = resolve(token);
        if (access.permission() != Permission.EDIT) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The link allows viewing only");
        }
        StoredDocument current = load(access);
        documents.update(access.ownerId(), id(user), access.documentId(),
                parser.parse(body.content(), current.meta().title()));
        return new SharedView(DocumentController.view(parser, load(access)), access.permission());
    }

    private SharedAccess resolve(String token) {
        return documents.resolveLink(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Share link not found or revoked"));
    }

    private StoredDocument load(SharedAccess access) {
        return documents.find(access.ownerId(), access.documentId())
                .orElseThrow(() -> new NotFoundException(access.documentId()));
    }
}
