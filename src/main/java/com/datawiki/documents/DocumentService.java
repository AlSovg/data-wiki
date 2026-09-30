package com.datawiki.documents;

import com.datawiki.markdown.ParsedDocument;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Documents and their versions in PostgreSQL. Every method takes the owner id and filters by it;
 * access checks for sharing would go here (see system-design, section 9).
 */
@Service
public class DocumentService {

    public enum Outcome { CREATED, DUPLICATE, UPDATED, UNCHANGED }

    /** @param id the new document, or, for {@code DUPLICATE}, the existing one with the same content */
    public record SaveResult(UUID id, Outcome outcome) {
    }

    public record DocumentMeta(UUID id, String title, List<String> tags, String category, String author,
                               int version, long sizeBytes, int wordCount, Instant createdAt, Instant updatedAt) {
    }

    public record StoredDocument(DocumentMeta meta, String content, Map<String, Object> extra) {
    }

    public record VersionSummary(int version, Instant createdAt, UUID createdBy) {
    }

    public record StoredVersion(VersionSummary summary, String content, Map<String, Object> extra) {
    }

    /** A live document as the indexer needs it; not owner-filtered, for internal use only. */
    public record IndexableDocument(UUID ownerId, DocumentMeta meta, String content) {
    }

    public record Page(List<DocumentMeta> items, int page, int size, long total) {
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(UUID id) {
            super("Document not found: " + id);
        }
    }

    /** The content equals another live document of the same owner. */
    public static class DuplicateContentException extends RuntimeException {
        public DuplicateContentException() {
            super("Another document has the same content");
        }
    }

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public DocumentService(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Stores a new document with version 1; identical content of a live document yields {@code DUPLICATE}. */
    @Transactional
    public SaveResult create(UUID ownerId, ParsedDocument doc) {
        UUID id = UUID.randomUUID();
        int inserted = jdbc.sql("""
                        insert into documents (id, owner_id, title, category, author, current_version,
                                               content_hash, size_bytes, word_count)
                        values (:id, :owner, :title, :category,
                                coalesce(:author, (select email from users where id = :owner)), 1,
                                :hash, :size, :words)
                        on conflict (owner_id, content_hash) where deleted_at is null do nothing
                        """)
                .param("id", id).param("owner", ownerId).param("title", doc.title())
                .param("category", doc.category()).param("author", doc.author()).param("hash", doc.contentHash())
                .param("size", doc.sizeBytes()).param("words", doc.wordCount())
                .update();
        if (inserted == 0) {
            UUID existing = jdbc.sql("select id from documents where owner_id = :owner and content_hash = :hash"
                            + " and deleted_at is null")
                    .param("owner", ownerId).param("hash", doc.contentHash())
                    .query(UUID.class).single();
            return new SaveResult(existing, Outcome.DUPLICATE);
        }
        insertVersion(id, 1, ownerId, doc);
        replaceTags(ownerId, id, doc.tags());
        enqueueIndexing(id);
        return new SaveResult(id, Outcome.CREATED);
    }

    /** Edit: a new version, unless the normalized content is unchanged. */
    @Transactional
    public SaveResult update(UUID ownerId, UUID id, ParsedDocument doc) {
        var current = jdbc.sql("select current_version, content_hash from documents"
                        + " where id = :id and owner_id = :owner and deleted_at is null for update")
                .param("id", id).param("owner", ownerId)
                .query((rs, n) -> Map.entry(rs.getInt(1), rs.getString(2).strip()))
                .optional().orElseThrow(() -> new NotFoundException(id));
        if (current.getValue().equals(doc.contentHash())) {
            return new SaveResult(id, Outcome.UNCHANGED);
        }
        int version = current.getKey() + 1;
        try {
            jdbc.sql("""
                            update documents set title = :title, category = :category,
                                   author = coalesce(:author, (select email from users where id = :owner)),
                                   current_version = :version, content_hash = :hash, size_bytes = :size,
                                   word_count = :words, updated_at = now()
                            where id = :id
                            """)
                    .param("id", id).param("owner", ownerId).param("title", doc.title())
                    .param("category", doc.category()).param("author", doc.author()).param("version", version)
                    .param("hash", doc.contentHash()).param("size", doc.sizeBytes()).param("words", doc.wordCount())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateContentException();
        }
        insertVersion(id, version, ownerId, doc);
        replaceTags(ownerId, id, doc.tags());
        enqueueIndexing(id);
        return new SaveResult(id, Outcome.UPDATED);
    }

    /** Soft delete; the row and its versions stay. */
    @Transactional
    public void delete(UUID ownerId, UUID id) {
        int n = jdbc.sql("update documents set deleted_at = now() where id = :id and owner_id = :owner"
                        + " and deleted_at is null")
                .param("id", id).param("owner", ownerId).update();
        if (n == 0) {
            throw new NotFoundException(id);
        }
        enqueueIndexing(id);
    }

    /** Live document by id regardless of owner; {@code empty} if it is deleted or unknown. */
    @Transactional(readOnly = true)
    public Optional<IndexableDocument> findForIndex(UUID id) {
        return jdbc.sql("""
                        select d.*, v.content
                        from documents d
                        join document_versions v on v.document_id = d.id and v.version = d.current_version
                        where d.id = :id and d.deleted_at is null
                        """)
                .param("id", id)
                .query((rs, n) -> new IndexableDocument(rs.getObject("owner_id", UUID.class),
                        meta(id, rs, n), rs.getString("content")))
                .optional();
    }

    @Transactional(readOnly = true)
    public Optional<StoredDocument> find(UUID ownerId, UUID id) {
        return jdbc.sql("""
                        select d.*, v.content, v.extra::text as extra_json
                        from documents d
                        join document_versions v on v.document_id = d.id and v.version = d.current_version
                        where d.id = :id and d.owner_id = :owner and d.deleted_at is null
                        """)
                .param("id", id).param("owner", ownerId)
                .query((rs, n) -> new StoredDocument(meta(rs.getObject("id", UUID.class), rs, n),
                        rs.getString("content"), extra(rs.getString("extra_json"))))
                .optional();
    }

    /** Newest first; empty for an unknown or foreign document. */
    @Transactional(readOnly = true)
    public List<VersionSummary> versions(UUID ownerId, UUID id) {
        return jdbc.sql("""
                        select v.version, v.created_at, v.created_by
                        from document_versions v join documents d on d.id = v.document_id
                        where d.id = :id and d.owner_id = :owner and d.deleted_at is null
                        order by v.version desc
                        """)
                .param("id", id).param("owner", ownerId)
                .query((rs, n) -> new VersionSummary(rs.getInt("version"), instant(rs, "created_at"),
                        rs.getObject("created_by", UUID.class)))
                .list();
    }

    @Transactional(readOnly = true)
    public Optional<StoredVersion> version(UUID ownerId, UUID id, int version) {
        return jdbc.sql("""
                        select v.version, v.created_at, v.created_by, v.content, v.extra::text as extra_json
                        from document_versions v join documents d on d.id = v.document_id
                        where d.id = :id and d.owner_id = :owner and d.deleted_at is null and v.version = :version
                        """)
                .param("id", id).param("owner", ownerId).param("version", version)
                .query((rs, n) -> new StoredVersion(
                        new VersionSummary(rs.getInt("version"), instant(rs, "created_at"),
                                rs.getObject("created_by", UUID.class)),
                        rs.getString("content"), extra(rs.getString("extra_json"))))
                .optional();
    }

    /** Metadata of the owner's live documents among {@code ids}; missing ones (e.g. just deleted) are absent. */
    @Transactional(readOnly = true)
    public Map<UUID, DocumentMeta> metas(UUID ownerId, List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, DocumentMeta> result = new java.util.HashMap<>();
        jdbc.sql("select * from documents where owner_id = :owner and deleted_at is null and id in (:ids)")
                .param("owner", ownerId).param("ids", ids)
                .query((rs, n) -> meta(rs.getObject("id", UUID.class), rs, n))
                .list().forEach(m -> result.put(m.id(), m));
        return result;
    }

    /** Newest first. Filters and sort options arrive with the API stage. */
    @Transactional(readOnly = true)
    public Page list(UUID ownerId, int page, int size) {
        long total = jdbc.sql("select count(*) from documents where owner_id = :owner and deleted_at is null")
                .param("owner", ownerId).query(Long.class).single();
        List<DocumentMeta> items = jdbc.sql("""
                        select * from documents where owner_id = :owner and deleted_at is null
                        order by updated_at desc, id limit :size offset :offset
                        """)
                .param("owner", ownerId).param("size", size).param("offset", (long) page * size)
                .query((rs, n) -> meta(rs.getObject("id", UUID.class), rs, n))
                .list();
        return new Page(items, page, size, total);
    }

    private void insertVersion(UUID id, int version, UUID userId, ParsedDocument doc) {
        jdbc.sql("insert into document_versions (document_id, version, content, extra, created_by)"
                        + " values (:id, :version, :content, cast(:extra as jsonb), :user)")
                .param("id", id).param("version", version).param("content", doc.content())
                .param("extra", json.writeValueAsString(doc.extra())).param("user", userId)
                .update();
    }

    /** Same transaction as the change, so the task is never lost between PostgreSQL and Lucene. */
    private void enqueueIndexing(UUID id) {
        jdbc.sql("insert into index_tasks (document_id) values (:id)").param("id", id).update();
    }

    private void replaceTags(UUID ownerId, UUID id, List<String> names) {
        jdbc.sql("delete from document_tags where document_id = :id").param("id", id).update();
        for (String name : names) {
            jdbc.sql("insert into tags (owner_id, name) values (:owner, :name) on conflict do nothing")
                    .param("owner", ownerId).param("name", name).update();
            jdbc.sql("insert into document_tags (document_id, tag_id)"
                            + " select :id, id from tags where owner_id = :owner and name = :name")
                    .param("id", id).param("owner", ownerId).param("name", name).update();
        }
    }

    /** N+1 on tags per listed document; 20 tags max and small pages make it cheap enough. */
    private DocumentMeta meta(UUID id, java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        List<String> tags = jdbc.sql("select t.name from document_tags dt join tags t on t.id = dt.tag_id"
                        + " where dt.document_id = :id order by t.name")
                .param("id", id).query(String.class).list();
        return new DocumentMeta(id, rs.getString("title"), tags, rs.getString("category"), rs.getString("author"),
                rs.getInt("current_version"), rs.getLong("size_bytes"), rs.getInt("word_count"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private Map<String, Object> extra(String jsonText) {
        return json.readValue(jsonText, MAP);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
