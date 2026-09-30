package com.datawiki.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.junit.jupiter.api.Test;

class WikiAnalyzerTest {

    final WikiAnalyzer analyzer = new WikiAnalyzer(true);

    List<String> tokens(WikiAnalyzer a, String text) throws IOException {
        List<String> out = new ArrayList<>();
        try (var stream = a.tokenStream(LuceneIndex.BODY, text)) {
            var term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                out.add(term.toString());
            }
            stream.end();
        }
        return out;
    }

    List<String> tokens(String text) throws IOException {
        return tokens(analyzer, text);
    }

    @Test
    void emptyAndPunctuationOnlyInputGiveNoTokens() throws IOException {
        assertThat(tokens("")).isEmpty();
        assertThat(tokens("  ...  !?, — ")).isEmpty();
    }

    @Test
    void lowerCasesBothAlphabets() throws IOException {
        assertThat(tokens(new WikiAnalyzer(false), "Москва LONDON")).containsExactly("москва", "london");
    }

    @Test
    void dropsRussianAndEnglishStopWords() throws IOException {
        assertThat(tokens(new WikiAnalyzer(false), "и не the of поиск")).containsExactly("поиск");
    }

    @Test
    void keepsNumbersAndSplitsOnPunctuation() throws IOException {
        assertThat(tokens(new WikiAnalyzer(false), "версия 2.5, java-25")).containsExactly("версия", "2.5", "java", "25");
    }

    @Test
    void mixedLanguageTextIsStemmedPerLanguage() throws IOException {
        assertThat(tokens("индексы indexes")).containsExactly("индекс", "index");
    }

    @Test
    void sameWordFormsProduceSameTerm() throws IOException {
        assertThat(tokens("документ")).isEqualTo(tokens("документами"));
        assertThat(tokens("search")).isEqualTo(tokens("searching"));
    }
}
