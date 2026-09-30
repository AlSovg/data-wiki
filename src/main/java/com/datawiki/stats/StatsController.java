package com.datawiki.stats;

import static com.datawiki.auth.CurrentUser.id;

import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Aggregates over the current user's live documents. */
@RestController
@RequestMapping("/api/stats")
class StatsController {

    private static final int TOP = 50;

    record Bucket(String key, long count) {
    }

    record Stats(long documents, long totalSizeBytes, long totalWords, List<Bucket> byTag, List<Bucket> byCategory,
                 List<Bucket> byAuthor) {
    }

    private final JdbcClient jdbc;

    StatsController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    Stats stats(Principal user) {
        UUID owner = id(user);
        long[] totals = jdbc.sql("""
                        select count(*) as n, coalesce(sum(size_bytes), 0) as bytes, coalesce(sum(word_count), 0) as words
                        from documents where owner_id = :owner and deleted_at is null
                        """)
                .param("owner", owner)
                .query((rs, i) -> new long[] {rs.getLong("n"), rs.getLong("bytes"), rs.getLong("words")})
                .single();
        return new Stats(totals[0], totals[1], totals[2],
                buckets("""
                        select t.name as key, count(*) as n
                        from documents d join document_tags dt on dt.document_id = d.id
                        join tags t on t.id = dt.tag_id
                        where d.owner_id = :owner and d.deleted_at is null group by t.name
                        """, owner),
                buckets("""
                        select category as key, count(*) as n from documents
                        where owner_id = :owner and deleted_at is null and category is not null group by category
                        """, owner),
                buckets("""
                        select author as key, count(*) as n from documents
                        where owner_id = :owner and deleted_at is null group by author
                        """, owner));
    }

    private List<Bucket> buckets(String sql, UUID owner) {
        return jdbc.sql("select * from (" + sql + ") b order by n desc, key limit " + TOP)
                .param("owner", owner)
                .query((rs, i) -> new Bucket(rs.getString("key"), rs.getLong("n")))
                .list();
    }
}
