package app.lyng.store;

import static app.lyng.LyngApplication.USER;

import app.lyng.model.Embedder;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 每个人物自己的亲历痕迹：TA 看到、听到、说过、做过的每一行，写成标明说话人的感知材料。
 * 由系统原样保存与索引，不依赖模型“记住”。
 */
@Repository
public class Traces {

    public record Trace(long id, String sourceKind, UUID sourceId, String sourceLabel, long refId, String text,
                        Instant createdAt) {}

    private static final String COLUMNS = "id, source_kind, source_id, source_label, ref_id, text, created_at";

    private final JdbcClient jdbc;

    public Traces(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void add(UUID characterId, String sourceKind, UUID sourceId, String sourceLabel, long refId, String text) {
        jdbc.sql("""
                INSERT INTO traces (user_id, character_id, source_kind, source_id, source_label, ref_id, text)
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (character_id, source_kind, ref_id) DO NOTHING""")
                .params(USER, characterId, sourceKind, sourceId, sourceLabel, refId, text).update();
    }

    public List<Trace> recent(UUID characterId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM (SELECT * FROM traces WHERE character_id = ? AND user_id = ? ORDER BY id DESC LIMIT ?) t ORDER BY id")
                .params(characterId, USER, limit).query(Trace.class).list();
    }

    /** 某个来源（一场戏 / 一段私聊）里的痕迹，按顺序分页。 */
    public List<Trace> bySource(UUID characterId, UUID sourceId, long afterId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM traces WHERE character_id = ? AND source_id = ? AND user_id = ? AND id > ? ORDER BY id LIMIT ?")
                .params(characterId, sourceId, USER, afterId, limit).query(Trace.class).list();
    }

    public record Source(String sourceKind, UUID sourceId, String sourceLabel, long lines, Instant firstAt, Instant lastAt) {}

    /** 人物亲历过的每一场戏、每一段私聊。 */
    public List<Source> sources(UUID characterId) {
        return jdbc.sql("""
                SELECT source_kind, source_id, max(source_label) AS source_label, count(*) AS lines,
                       min(created_at) AS first_at, max(created_at) AS last_at
                FROM traces WHERE character_id = ? AND user_id = ?
                GROUP BY source_kind, source_id ORDER BY max(created_at) DESC""")
                .params(characterId, USER).query(Source.class).list();
    }

    public record Unindexed(long id, String text) {}

    public List<Unindexed> unindexed(UUID characterId, int limit) {
        return jdbc.sql("SELECT id, text FROM traces WHERE character_id = ? AND user_id = ? AND embedding IS NULL ORDER BY id LIMIT ?")
                .params(characterId, USER, limit).query(Unindexed.class).list();
    }

    public void index(long id, float[] vector) {
        jdbc.sql("UPDATE traces SET embedding = ?::vector WHERE id = ?").params(Embedder.literal(vector), id).update();
    }

    /**
     * 与查询向量最相近的痕迹 id。已经在上下文里的不要：来源为 excludeSource 且 ref_id 不小于 excludeFromRef 的行。
     */
    public List<Long> similar(UUID characterId, float[] query, UUID excludeSource, long excludeFromRef, int limit) {
        return jdbc.sql("""
                SELECT id FROM traces WHERE character_id = ? AND user_id = ? AND embedding IS NOT NULL
                  AND vector_dims(embedding) = ?
                  AND NOT (source_id = CAST(? AS uuid) AND ref_id >= ?)
                ORDER BY embedding <=> ?::vector LIMIT ?""")
                .params(characterId, USER, query.length, excludeSource, excludeFromRef, Embedder.literal(query), limit)
                .query(Long.class).list();
    }

    /** 一条痕迹前后同一来源的几行，让召回的原话带着上下文。 */
    public List<Trace> window(UUID characterId, long id, int before, int after) {
        return jdbc.sql("""
                WITH hit AS (SELECT source_id FROM traces WHERE id = ? AND character_id = ?)
                (SELECT %1$s FROM traces WHERE character_id = ? AND source_id = (SELECT source_id FROM hit) AND id <= ?
                 ORDER BY id DESC LIMIT ?)
                UNION
                (SELECT %1$s FROM traces WHERE character_id = ? AND source_id = (SELECT source_id FROM hit) AND id > ?
                 ORDER BY id LIMIT ?)
                ORDER BY id""".formatted(COLUMNS))
                .params(id, characterId, characterId, id, before + 1, characterId, id, after)
                .query(Trace.class).list();
    }
}
