package com.datawiki.scoring;

import java.io.IOException;
import java.util.List;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.Query;

/** A way of scoring documents for a query. Register a new one as a Spring bean; the hybrid refers to it by name. */
public interface ScoringMethod {

    /** Lucene's internal doc id (valid for the reader it came from) and the method's score. */
    record Scored(int doc, float score) {
    }

    String name();

    /** Top {@code topK} matches of {@code query}, best first. */
    List<Scored> search(IndexReader reader, Query query, int topK) throws IOException;
}
