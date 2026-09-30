package com.datawiki.indexing;

import com.datawiki.documents.DocumentService;
import com.datawiki.markdown.MarkdownParser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains {@code index_tasks} into Lucene. Tasks are idempotent ("bring this document in line with PostgreSQL"),
 * so a crash between the Lucene commit and the task cleanup only repeats work.
 */
@Component
public class IndexWorker {

    private static final Logger log = LoggerFactory.getLogger(IndexWorker.class);

    private final JdbcClient jdbc;
    private final DocumentService documents;
    private final MarkdownParser parser;
    private final LuceneIndex index;
    private final int batchSize;
    private final int maxAttempts;

    public IndexWorker(JdbcClient jdbc, DocumentService documents, MarkdownParser parser, LuceneIndex index,
                       @Value("${app.index.batch-size}") int batchSize,
                       @Value("${app.index.max-attempts}") int maxAttempts) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.parser = parser;
        this.index = index;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${app.index.poll-ms}", initialDelayString = "${app.index.poll-ms}")
    void poll() {
        try {
            while (processPending() == batchSize) {
                // more tasks waiting
            }
        } catch (RuntimeException e) {
            log.error("Index worker failed", e);
        }
    }

    /**
     * Handles one batch of tasks, oldest first, and returns how many it fetched. A failing document gets its
     * attempt counter raised and is retried on the next poll until {@code max-attempts}; others are unaffected.
     */
    public synchronized int processPending() {
        record Task(long id, UUID documentId) { }
        var tasks = jdbc.sql("select id, document_id from index_tasks where attempts < :max order by id limit :limit")
                .param("max", maxAttempts).param("limit", batchSize)
                .query((rs, n) -> new Task(rs.getLong("id"), rs.getObject("document_id", UUID.class)))
                .list();
        if (tasks.isEmpty()) {
            return 0;
        }

        // last task id per document: later tasks for the same document are picked up by the next batch
        Map<UUID, Long> lastTask = new LinkedHashMap<>();
        tasks.forEach(t -> lastTask.merge(t.documentId(), t.id(), Math::max));

        Map<UUID, Long> done = new LinkedHashMap<>();
        lastTask.forEach((documentId, taskId) -> {
            try {
                apply(documentId);
                done.put(documentId, taskId);
            } catch (RuntimeException e) {
                log.warn("Indexing of {} failed", documentId, e);
                jdbc.sql("update index_tasks set attempts = attempts + 1, last_error = :error"
                                + " where document_id = :doc and id <= :id")
                        .param("error", String.valueOf(e)).param("doc", documentId).param("id", taskId).update();
            }
        });

        index.commit();
        done.forEach((documentId, taskId) -> jdbc
                .sql("delete from index_tasks where document_id = :doc and id <= :id")
                .param("doc", documentId).param("id", taskId).update());
        return tasks.size();
    }

    /** Empties the index and queues every live document; the worker refills it. */
    public synchronized void rebuild() {
        index.clear();
        index.commit();
        jdbc.sql("insert into index_tasks (document_id) select id from documents where deleted_at is null").update();
    }

    private void apply(UUID documentId) {
        documents.findForIndex(documentId).ifPresentOrElse(
                doc -> index.upsert(doc.ownerId(), doc.meta(), parser.parse(doc.content(), doc.meta().title())),
                () -> index.delete(documentId));
    }
}
