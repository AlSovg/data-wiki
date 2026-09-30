package com.datawiki.indexing;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.ru.RussianAnalyzer;
import org.apache.lucene.analysis.snowball.SnowballFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tartarus.snowball.ext.EnglishStemmer;
import org.tartarus.snowball.ext.RussianStemmer;

/**
 * One analyzer for indexing and querying: lower case, RU+EN stop words, Snowball stemming for both languages.
 * The Russian stemmer leaves Latin words alone and the English one leaves Cyrillic alone, so the two filters
 * are simply chained instead of detecting the language per token.
 */
@Component
public class WikiAnalyzer extends Analyzer {

    private static final CharArraySet STOP_WORDS = stopWords();

    private final boolean stemming;

    public WikiAnalyzer(@Value("${app.lucene.stemming:true}") boolean stemming) {
        this.stemming = stemming;
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        Tokenizer source = new StandardTokenizer();
        TokenStream stream = new StopFilter(new LowerCaseFilter(source), STOP_WORDS);
        if (stemming) {
            stream = new SnowballFilter(new SnowballFilter(stream, new RussianStemmer()), new EnglishStemmer());
        }
        return new TokenStreamComponents(source, stream);
    }

    private static CharArraySet stopWords() {
        CharArraySet set = new CharArraySet(RussianAnalyzer.getDefaultStopSet(), false);
        set.addAll(EnglishAnalyzer.getDefaultStopSet());
        return CharArraySet.unmodifiableSet(set);
    }
}
