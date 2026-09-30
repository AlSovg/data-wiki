package com.datawiki.search;

import com.datawiki.scoring.ScoringRegistry;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** A user's default scoring method and hybrid weights, used when a search names no method. */
@Service
public class SearchSettingsService {

    /** @param weights need not sum to 1; {@code null} means equal */
    public record Settings(String method, Map<String, Double> weights) {
    }

    private static final Settings DEFAULT = new Settings("bm25", null);
    private static final TypeReference<Map<String, Double>> WEIGHTS = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final ScoringRegistry scoring;

    public SearchSettingsService(JdbcClient jdbc, JsonMapper json, ScoringRegistry scoring) {
        this.jdbc = jdbc;
        this.json = json;
        this.scoring = scoring;
    }

    public Settings get(UUID userId) {
        return jdbc.sql("select method, weights::text as weights from search_settings where user_id = :user")
                .param("user", userId)
                .query((rs, n) -> new Settings(rs.getString("method"),
                        rs.getString("weights") == null ? null : json.readValue(rs.getString("weights"), WEIGHTS)))
                .optional().orElse(DEFAULT);
    }

    /** @throws IllegalArgumentException unknown method or invalid weights */
    public Settings save(UUID userId, Settings settings) {
        if (settings.method() == null || !scoring.isKnown(settings.method())) {
            throw new IllegalArgumentException("Unknown scoring method: " + settings.method());
        }
        if (settings.weights() != null) {
            scoring.normalize(settings.weights()); // validation only; weights are stored as given
        }
        jdbc.sql("""
                        insert into search_settings (user_id, method, weights)
                        values (:user, :method, cast(:weights as jsonb))
                        on conflict (user_id) do update set method = excluded.method, weights = excluded.weights
                        """)
                .param("user", userId).param("method", settings.method())
                .param("weights", settings.weights() == null ? null : json.writeValueAsString(settings.weights()))
                .update();
        return settings;
    }
}
