package com.datawiki.cache;

import com.datawiki.search.SearchService.SearchResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Redis cache of search results. The key holds the user id and the index generation, which
 * {@link #bumpGeneration()} raises on every index commit, so entries of an older index state are never hit and
 * simply expire by TTL. Redis failures never break a search: they are logged and treated as a miss.
 */
@Component
public class SearchCache {

    private static final Logger log = LoggerFactory.getLogger(SearchCache.class);
    static final String GENERATION_KEY = "index:gen";

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final Duration ttl;

    public SearchCache(StringRedisTemplate redis, JsonMapper json,
                       @Value("${app.cache.ttl-seconds}") long ttlSeconds) {
        this.redis = redis;
        this.json = json;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    /** Current index generation; used to build a key before the search runs. Empty if Redis is unavailable. */
    public Optional<String> key(UUID ownerId, String canonicalRequest) {
        try {
            String gen = redis.opsForValue().get(GENERATION_KEY);
            return Optional.of("search:" + ownerId + ":" + (gen == null ? "0" : gen) + ":" + sha256(canonicalRequest));
        } catch (RuntimeException e) {
            log.warn("Search cache unavailable, skipping", e);
            return Optional.empty();
        }
    }

    public Optional<SearchResult> get(String key) {
        try {
            String value = redis.opsForValue().get(key);
            return value == null ? Optional.empty() : Optional.of(json.readValue(value, SearchResult.class));
        } catch (RuntimeException e) {
            log.warn("Search cache read failed", e);
            return Optional.empty();
        }
    }

    public void put(String key, SearchResult result) {
        try {
            redis.opsForValue().set(key, json.writeValueAsString(result), ttl);
        } catch (RuntimeException e) {
            log.warn("Search cache write failed", e);
        }
    }

    /**
     * Invalidates every cached result. If Redis is down, entries written before the outage may be served until
     * their TTL runs out.
     */
    public void bumpGeneration() {
        try {
            redis.opsForValue().increment(GENERATION_KEY);
        } catch (RuntimeException e) {
            log.warn("Search cache invalidation failed; stale results possible until TTL", e);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
