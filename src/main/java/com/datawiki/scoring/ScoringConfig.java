package com.datawiki.scoring;

import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ScoringConfig {

    @Bean
    ScoringMethod bm25(@Value("${app.search.bm25.k1:1.2}") float k1, @Value("${app.search.bm25.b:0.75}") float b) {
        return new SimilarityMethod("bm25", new BM25Similarity(k1, b));
    }

    /** Lucene's classic TF-IDF: idf = ln((N+1)/(n+1)) + 1, tf = sqrt(f), length norm 1/sqrt(dl). */
    @Bean
    ScoringMethod tfidf() {
        return new SimilarityMethod("tfidf", new ClassicSimilarity());
    }
}
