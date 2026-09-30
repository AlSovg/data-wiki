package com.datawiki.scoring;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.similarities.Similarity;

/**
 * Scores with a Lucene {@link Similarity}. A fresh searcher over the shared reader is made per call, so methods
 * with different similarities never touch each other's searcher.
 */
public class SimilarityMethod implements ScoringMethod {

    private final String name;
    private final Similarity similarity;

    public SimilarityMethod(String name, Similarity similarity) {
        this.name = name;
        this.similarity = similarity;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<Scored> search(IndexReader reader, Query query, int topK) throws IOException {
        IndexSearcher searcher = new IndexSearcher(reader);
        searcher.setSimilarity(similarity);
        return Arrays.stream(searcher.search(query, topK).scoreDocs).map(d -> new Scored(d.doc, d.score)).toList();
    }
}
