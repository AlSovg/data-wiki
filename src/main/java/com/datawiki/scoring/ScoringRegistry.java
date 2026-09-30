package com.datawiki.scoring;

import com.datawiki.scoring.ScoringMethod.Scored;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.Query;
import org.springframework.stereotype.Component;

/** Scoring methods by name, plus the hybrid, which combines the registered ones (docs/scoring.md). */
@Component
public class ScoringRegistry {

    public static final String HYBRID = "hybrid";

    private final Map<String, ScoringMethod> methods = new LinkedHashMap<>();

    public ScoringRegistry(List<ScoringMethod> beans) {
        beans.forEach(m -> methods.put(m.name(), m));
    }

    /** Registered method names followed by {@code hybrid}. */
    public List<String> names() {
        List<String> names = new ArrayList<>(methods.keySet());
        names.add(HYBRID);
        return names;
    }

    public boolean isKnown(String name) {
        return HYBRID.equals(name) || methods.containsKey(name);
    }

    /** Equal weights over all registered methods. */
    public Map<String, Double> defaultWeights() {
        Map<String, Double> weights = new LinkedHashMap<>();
        methods.keySet().forEach(name -> weights.put(name, 1.0));
        return normalize(weights);
    }

    /**
     * Scores with a single method, or with the hybrid when {@code method} is {@code hybrid}.
     *
     * @param weights hybrid weights, need not sum to 1; {@code null} means equal weights; ignored otherwise
     * @throws IllegalArgumentException unknown method, unknown name in weights, negative or all-zero weights
     */
    public List<Scored> search(String method, Map<String, Double> weights, IndexReader reader, Query query, int topK)
            throws IOException {
        if (!HYBRID.equals(method)) {
            return get(method).search(reader, query, topK);
        }
        Map<String, Double> normalized = weights == null ? defaultWeights() : normalize(weights);
        int k = Math.max(topK, 100);
        Map<String, List<Scored>> lists = new HashMap<>();
        for (String name : normalized.keySet()) {
            lists.put(name, get(name).search(reader, query, k));
        }
        List<Scored> combined = combine(lists, normalized);
        return combined.subList(0, Math.min(topK, combined.size()));
    }

    private ScoringMethod get(String name) {
        ScoringMethod method = methods.get(name);
        if (method == null) {
            throw new IllegalArgumentException("Unknown scoring method: " + name);
        }
        return method;
    }

    /** Scales to sum 1 and rejects unknown names, negative values and a zero sum. */
    Map<String, Double> normalize(Map<String, Double> weights) {
        double sum = 0;
        for (var e : weights.entrySet()) {
            if (!methods.containsKey(e.getKey())) {
                throw new IllegalArgumentException("Unknown scoring method in weights: " + e.getKey());
            }
            if (e.getValue() == null || e.getValue() < 0 || e.getValue().isNaN()) {
                throw new IllegalArgumentException("Weight of " + e.getKey() + " must not be negative");
            }
            sum += e.getValue();
        }
        if (sum <= 0) {
            throw new IllegalArgumentException("Weights must have a positive sum");
        }
        Map<String, Double> result = new TreeMap<>();
        for (var e : weights.entrySet()) {
            result.put(e.getKey(), e.getValue() / sum);
        }
        return result;
    }

    /**
     * Min-max normalizes every list, then sums {@code weight * normalizedScore} per document; a document missing
     * from a list gets 0 from it. All scores equal in a list (including a single result) normalize to 1.
     */
    static List<Scored> combine(Map<String, List<Scored>> lists, Map<String, Double> weights) {
        Map<Integer, Double> total = new HashMap<>();
        lists.forEach((name, list) -> {
            if (list.isEmpty()) {
                return;
            }
            double min = list.stream().mapToDouble(Scored::score).min().getAsDouble();
            double max = list.stream().mapToDouble(Scored::score).max().getAsDouble();
            for (Scored s : list) {
                double normalized = max == min ? 1 : (s.score() - min) / (max - min);
                total.merge(s.doc(), weights.get(name) * normalized, Double::sum);
            }
        });
        return total.entrySet().stream()
                .map(e -> new Scored(e.getKey(), e.getValue().floatValue()))
                .sorted(Comparator.comparingDouble(Scored::score).reversed().thenComparingInt(Scored::doc))
                .toList();
    }
}
