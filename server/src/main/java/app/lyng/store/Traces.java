package app.lyng.store;

import static app.lyng.LyngApplication.USER;

import app.lyng.model.Embedder;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 每个人物自己的亲历档案：TA 看到、听到、说过、做过的每一行，写成标明说话人的感知材料；
 * TA 自己形成的记忆也各占一行（source_kind = memory）。由系统原样保存与索引，不依赖模型“记住”。
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
                VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (character_id, source_kind, source_id, ref_id) DO NOTHING""")
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
                FROM traces WHERE character_id = ? AND user_id = ? AND source_kind <> 'memory'
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
                  AND source_kind <> 'memory' AND vector_dims(embedding) = ?
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

    // ---- 人物自己翻找时用的档案 ----

    /** 相关的上限：余弦距离超过它的不算相关。 */
    public static final double RELATED = 0.45;

    /** 一条记忆在档案里的那一行。 */
    public long addMemory(UUID characterId, UUID memoryId, String text) {
        return jdbc.sql("""
                INSERT INTO traces (user_id, character_id, source_kind, source_id, source_label, ref_id, text)
                VALUES (?, ?, 'memory', ?, '你形成的记忆', 0, ?) RETURNING id""")
                .params(USER, characterId, memoryId, text).query(Long.class).single();
    }

    public Optional<Trace> find(UUID characterId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM traces WHERE character_id = ? AND id = ? AND user_id = ?")
                .params(characterId, id, USER).query(Trace.class).optional();
    }

    /** 最近的几条，新的在前（各个来源都算，记忆也算）。 */
    public List<Trace> newest(UUID characterId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM traces WHERE character_id = ? AND user_id = ? ORDER BY created_at DESC, id DESC LIMIT ?")
                .params(characterId, USER, limit).query(Trace.class).list();
    }

    /** 与查询向量相关的（距离不超过 RELATED），近的在前。 */
    public List<Trace> related(UUID characterId, float[] query, int limit) {
        String vector = Embedder.literal(query);
        return jdbc.sql("SELECT " + COLUMNS + " " + """
                 FROM traces WHERE character_id = ? AND user_id = ? AND embedding IS NOT NULL
                  AND vector_dims(embedding) = ? AND (embedding <=> ?::vector) <= ?
                ORDER BY embedding <=> ?::vector, id LIMIT ?""")
                .params(characterId, USER, query.length, vector, RELATED, vector, limit).query(Trace.class).list();
    }

    /** 比某一条晚、又与它相关的几条：可能是它的后续，也可能无关。 */
    public List<Trace> relatedLater(UUID characterId, long id, int limit) {
        return jdbc.sql("""
                SELECT t.id, t.source_kind, t.source_id, t.source_label, t.ref_id, t.text, t.created_at
                FROM traces t, traces r
                WHERE r.id = ? AND r.character_id = ? AND r.embedding IS NOT NULL
                  AND t.character_id = r.character_id AND t.user_id = ? AND t.embedding IS NOT NULL
                  AND vector_dims(t.embedding) = vector_dims(r.embedding) AND t.created_at > r.created_at
                  AND (t.embedding <=> r.embedding) <= ?
                ORDER BY t.embedding <=> r.embedding, t.created_at DESC, t.id LIMIT ?""")
                .params(id, characterId, USER, RELATED, limit).query(Trace.class).list();
    }

    /**
     * 人物自己提出过的、与这一条直接相连的几条（纠正在前，然后新的在前）。只展开一层：
     * 一条记忆可以借它依据的原话找到它们的后续，但相连的不会再接着串下去。
     */
    public List<Trace> developments(UUID characterId, long id, int offset, int limit) {
        return jdbc.sql("""
                WITH memory_sources AS (
                    SELECT d.earlier_id AS id FROM developments d JOIN traces m ON m.id = d.later_id
                    WHERE d.character_id = ? AND d.later_id = ? AND m.source_kind = 'memory'
                    ORDER BY d.earlier_id LIMIT 8
                ), anchors AS (
                    SELECT id FROM memory_sources UNION SELECT CAST(? AS bigint)
                ), neighbors AS (
                    SELECT d.later_id AS id, d.correction FROM developments d
                    WHERE d.character_id = ? AND d.earlier_id IN (SELECT id FROM anchors)
                    UNION ALL
                    SELECT d.earlier_id AS id, d.correction FROM developments d
                    WHERE d.character_id = ? AND d.later_id = ?
                ), candidates AS (
                    SELECT id, bool_or(correction) AS correction FROM neighbors WHERE id <> ? GROUP BY id
                )
                SELECT t.id, t.source_kind, t.source_id, t.source_label, t.ref_id, t.text, t.created_at
                FROM traces t JOIN candidates c ON c.id = t.id
                WHERE t.character_id = ? AND t.user_id = ?
                ORDER BY c.correction DESC, t.created_at DESC, t.id LIMIT ? OFFSET ?""")
                .params(characterId, id, id, characterId, characterId, id, id, characterId, USER, limit, offset)
                .query(Trace.class).list();
    }

    /** 记下两条之间的关系（较早的在前）；同一对已有时，纠正只会加上，不会抹去。 */
    public void link(UUID characterId, long earlier, long later, boolean correction, UUID runId) {
        jdbc.sql("""
                INSERT INTO developments (character_id, user_id, earlier_id, later_id, correction, run_id)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (character_id, earlier_id, later_id)
                DO UPDATE SET correction = developments.correction OR EXCLUDED.correction""")
                .params(characterId, USER, earlier, later, correction, runId).update();
    }

    /** 私聊里的一行：谁、说还是心声、原话。id 是档案里的 id。 */
    public record ChatLine(long id, UUID speakerId, String kind, String text, Instant createdAt) {}

    /** 这个人物在一段私聊里亲历的话（id 小于 beforeId 的最后 limit 行），按先后排。 */
    public List<ChatLine> chat(UUID characterId, UUID conversationId, long beforeId, int limit) {
        return jdbc.sql("""
                SELECT * FROM (
                    SELECT t.id, m.speaker_id, m.kind, m.text, t.created_at FROM traces t
                    JOIN messages m ON m.id = t.ref_id
                    WHERE t.character_id = ? AND t.user_id = ? AND t.source_kind = 'chat' AND t.source_id = ?
                      AND t.id < ? ORDER BY t.id DESC LIMIT ?) x ORDER BY id""")
                .params(characterId, USER, conversationId, beforeId, limit).query(ChatLine.class).list();
    }
}
