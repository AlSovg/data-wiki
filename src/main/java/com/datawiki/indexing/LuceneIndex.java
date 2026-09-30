package com.datawiki.indexing;

import com.datawiki.documents.DocumentService.DocumentMeta;
import com.datawiki.markdown.ParsedDocument;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.UUID;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.LongField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.IOFunction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The on-disk Lucene index: one Lucene document per wiki document (current version only), keyed by {@code id}.
 * Single instance per index directory (Lucene allows one writer). Changes become visible to searchers after
 * {@link #commit()}. Scoring is chosen at search time; the default similarity here only writes the norms,
 * which BM25 and TF-IDF share (see system-design, section 4).
 */
@Component
public class LuceneIndex implements AutoCloseable {

    public static final String ID = "id";
    public static final String OWNER_ID = "owner_id";
    public static final String TITLE = "title";
    public static final String HEADINGS = "headings";
    public static final String BODY = "body";
    public static final String CODE = "code";
    /** Exact tag values, for filters. */
    public static final String TAGS = "tags";
    /** Analyzed tags, for scoring. */
    public static final String TAGS_TEXT = "tags_text";
    public static final String CATEGORY = "category";
    public static final String AUTHOR = "author";
    public static final String UPDATED_AT = "updated_at";
    public static final String SIZE_BYTES = "size_bytes";

    private final Directory directory;
    private final IndexWriter writer;
    private final SearcherManager searchers;

    public LuceneIndex(@Value("${app.lucene.index-dir}") Path dir, WikiAnalyzer analyzer) throws IOException {
        this.directory = FSDirectory.open(dir);
        this.writer = new IndexWriter(directory, new IndexWriterConfig(analyzer));
        this.searchers = new SearcherManager(writer, null);
    }

    /** Replaces the indexed document with the same id. */
    public void upsert(UUID ownerId, DocumentMeta meta, ParsedDocument parsed) {
        Document doc = new Document();
        doc.add(new StringField(ID, meta.id().toString(), Store.YES));
        doc.add(new StringField(OWNER_ID, ownerId.toString(), Store.NO));
        doc.add(new TextField(TITLE, meta.title(), Store.YES));
        doc.add(new TextField(HEADINGS, parsed.headings(), Store.YES));
        doc.add(new TextField(BODY, parsed.body(), Store.YES));
        doc.add(new TextField(CODE, parsed.code(), Store.NO));
        for (String tag : meta.tags()) {
            doc.add(new StringField(TAGS, tag, Store.NO));
        }
        doc.add(new TextField(TAGS_TEXT, String.join(" ", meta.tags()), Store.NO));
        if (meta.category() != null) {
            doc.add(new StringField(CATEGORY, meta.category(), Store.NO));
        }
        doc.add(new StringField(AUTHOR, meta.author(), Store.NO));
        doc.add(new LongField(UPDATED_AT, meta.updatedAt().toEpochMilli(), Store.NO));
        doc.add(new LongField(SIZE_BYTES, meta.sizeBytes(), Store.NO));
        try {
            writer.updateDocument(new Term(ID, meta.id().toString()), doc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void delete(UUID documentId) {
        try {
            writer.deleteDocuments(new Term(ID, documentId.toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Drops every document; used before a rebuild. Takes effect on {@link #commit()}. */
    public void clear() {
        try {
            writer.deleteAll();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Makes upserts and deletes durable and visible to {@link #search}. */
    public void commit() {
        try {
            writer.commit();
            searchers.maybeRefreshBlocking();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Runs {@code action} on a searcher over the last committed state; the searcher must not escape. */
    public <T> T search(IOFunction<IndexSearcher, T> action) {
        try {
            IndexSearcher searcher = searchers.acquire();
            try {
                return action.apply(searcher);
            } finally {
                searchers.release(searcher);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public int size() {
        return search(s -> s.getIndexReader().numDocs());
    }

    @Override
    public void close() throws IOException {
        searchers.close();
        writer.close();
        directory.close();
    }
}
