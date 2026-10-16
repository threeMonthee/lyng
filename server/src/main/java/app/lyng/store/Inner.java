package app.lyng.store;

import static app.lyng.LyngApplication.USER;

import app.lyng.model.Embedder;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 人物的内心：如今的自我、看法、牵挂、记忆。只有人物自己（开口时、整理时或诞生时）写入；用户只能看。 */
@Repository
public class Inner {

    public record Self(String text, UUID runId, Instant createdAt) {}

    public record View(UUID otherId, String text, UUID runId, Instant createdAt) {}

    /** cue 是人物自己写的线索；诞生与整理写下的没有，这时用内容开头代替。 */
    public record Concern(UUID id, UUID aboutId, String cue, String text, String status, String resolution, UUID runId,
                          Instant createdAt, Instant updatedAt) {
        public String cueOrText() {
            return cue != null ? cue : Inner.abbreviate(text);
        }
    }

    /** content 是发生了什么与当时的意义；整理写下的把意义另放在 meaning。sources 是依据的亲历痕迹。 */
    public record Memory(UUID id, String cue, String content, String meaning, boolean permanent, UUID aboutId,
                         List<Long> sources, Long traceId, List<UUID> people, String sourceKind, UUID sourceId,
                         String sourceLabel, UUID runId, Instant createdAt) {
        public String cueOrText() {
            return cue != null ? cue : Inner.abbreviate(content);
        }

        /** 记忆的全文：内容，有意义时接在后面。 */
        public String text() {
            return meaning == null || meaning.isBlank() ? content : content + "——" + meaning;
        }
    }

    /** 没有线索时用内容开头的一小段。 */
    public static String abbreviate(String text) {
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= 24 ? flat : flat.substring(0, 24) + "…";
    }

    private final JdbcClient jdbc;
    private final Traces traces;

    public Inner(JdbcClient jdbc, Traces traces) {
        this.jdbc = jdbc;
        this.traces = traces;
    }

    // ---- 自我 ----

    public Optional<Self> self(UUID characterId) {
        return jdbc.sql("SELECT text, run_id, created_at FROM selves WHERE character_id = ? AND user_id = ? ORDER BY id DESC LIMIT 1")
                .params(characterId, USER).query(Self.class).optional();
    }

    public List<Self> selfHistory(UUID characterId) {
        return jdbc.sql("SELECT text, run_id, created_at FROM selves WHERE character_id = ? AND user_id = ? ORDER BY id DESC")
                .params(characterId, USER).query(Self.class).list();
    }

    public void addSelf(UUID characterId, String text, UUID runId) {
        jdbc.sql("INSERT INTO selves (user_id, character_id, text, run_id) VALUES (?, ?, ?, ?)")
                .params(USER, characterId, text, runId).update();
    }

    // ---- 看法 ----

    /** 每个人当前的看法（最新一条）。 */
    public List<View> views(UUID characterId) {
        return jdbc.sql("""
                SELECT DISTINCT ON (other_id) other_id, text, run_id, created_at FROM views
                WHERE character_id = ? AND user_id = ? ORDER BY other_id, id DESC""")
                .params(characterId, USER).query(View.class).list();
    }

    public Optional<View> view(UUID characterId, UUID otherId) {
        return jdbc.sql("""
                SELECT other_id, text, run_id, created_at FROM views
                WHERE character_id = ? AND other_id = ? AND user_id = ? ORDER BY id DESC LIMIT 1""")
                .params(characterId, otherId, USER).query(View.class).optional();
    }

    public List<View> viewHistory(UUID characterId, UUID otherId) {
        return jdbc.sql("""
                SELECT other_id, text, run_id, created_at FROM views
                WHERE character_id = ? AND other_id = ? AND user_id = ? ORDER BY id DESC""")
                .params(characterId, otherId, USER).query(View.class).list();
    }

    public void addView(UUID characterId, UUID otherId, String text, UUID runId) {
        jdbc.sql("INSERT INTO views (user_id, character_id, other_id, text, run_id) VALUES (?, ?, ?, ?, ?)")
                .params(USER, characterId, otherId, text, runId).update();
    }

    // ---- 牵挂 ----

    private static final String CONCERN_COLUMNS = "id, about_id, cue, text, status, resolution, run_id, created_at, updated_at";

    public List<Concern> concerns(UUID characterId) {
        return jdbc.sql("SELECT " + CONCERN_COLUMNS + " " + """
                 FROM concerns WHERE character_id = ? AND user_id = ?
                ORDER BY CASE status WHEN 'active' THEN 0 WHEN 'background' THEN 1 ELSE 2 END, updated_at DESC""")
                .params(characterId, USER).query(Concern.class).list();
    }

    /** 仍在心上的牵挂（活跃），最近改动的在前。 */
    public List<Concern> activeConcerns(UUID characterId, int limit) {
        return jdbc.sql("SELECT " + CONCERN_COLUMNS + " " + """
                 FROM concerns WHERE character_id = ? AND user_id = ? AND status = 'active'
                ORDER BY updated_at DESC LIMIT ?""")
                .params(characterId, USER, limit).query(Concern.class).list();
    }

    /** 关于某人的活跃牵挂。 */
    public List<Concern> activeConcernsAbout(UUID characterId, UUID aboutId) {
        return jdbc.sql("SELECT " + CONCERN_COLUMNS + " " + """
                 FROM concerns WHERE character_id = ? AND about_id = ? AND user_id = ? AND status = 'active'
                ORDER BY updated_at DESC""")
                .params(characterId, aboutId, USER).query(Concern.class).list();
    }

    public Optional<Concern> concern(UUID characterId, UUID id) {
        return jdbc.sql("SELECT " + CONCERN_COLUMNS + " FROM concerns WHERE character_id = ? AND id = ? AND user_id = ?")
                .params(characterId, id, USER).query(Concern.class).optional();
    }

    /** 按线索翻牵挂：query 为空时按顺序分页，否则在线索与内容里找包含它的。 */
    public List<Concern> browseConcerns(UUID characterId, String query, int offset, int limit) {
        return jdbc.sql("SELECT " + CONCERN_COLUMNS + " " + """
                 FROM concerns WHERE character_id = ? AND user_id = ?
                  AND (? = '' OR strpos(lower(coalesce(cue, '') || text), lower(?)) > 0)
                ORDER BY updated_at DESC, id LIMIT ? OFFSET ?""")
                .params(characterId, USER, query, query, limit, offset).query(Concern.class).list();
    }

    public UUID addConcern(UUID characterId, UUID aboutId, String text, UUID runId) {
        return putConcern(characterId, null, aboutId, null, text, "active", runId);
    }

    /** 新建（id 为空）或改写一件牵挂，写成完整的当前内容。 */
    public UUID putConcern(UUID characterId, UUID id, UUID aboutId, String cue, String text, String status, UUID runId) {
        if (id == null) {
            UUID created = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO concerns (id, user_id, character_id, about_id, cue, text, status, run_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(created, USER, characterId, aboutId, cue, text, status, runId).update();
            return created;
        }
        jdbc.sql("""
                UPDATE concerns SET about_id = ?, cue = ?, text = ?, status = ?, run_id = ?, updated_at = now()
                WHERE id = ? AND character_id = ? AND user_id = ?""")
                .params(aboutId, cue, text, status, runId, id, characterId, USER).update();
        return id;
    }

    public void resolveConcern(UUID id, String resolution, UUID runId) {
        jdbc.sql("UPDATE concerns SET status = 'resolved', resolution = ?, run_id = ?, updated_at = now() WHERE id = ? AND user_id = ?")
                .params(resolution == null || resolution.isBlank() ? null : resolution, runId, id, USER).update();
    }

    public void backgroundConcern(UUID id, UUID runId) {
        jdbc.sql("UPDATE concerns SET status = 'background', run_id = ?, updated_at = now() WHERE id = ? AND user_id = ?")
                .params(runId, id, USER).update();
    }

    public void reviseConcern(UUID id, String text, UUID runId) {
        jdbc.sql("UPDATE concerns SET text = ?, status = 'active', run_id = ?, updated_at = now() WHERE id = ? AND user_id = ?")
                .params(text, runId, id, USER).update();
    }

    // ---- 记忆 ----

    private static final String MEMORY_COLUMNS =
            "id, cue, content, meaning, permanent, about_id, sources, trace_id, people, source_kind, source_id, source_label, run_id, created_at";

    /** 整理时写下的记忆：发生了什么与意义分开写。 */
    public UUID addMemory(UUID characterId, String event, String meaning, List<UUID> people, String sourceKind,
                          UUID sourceId, String sourceLabel, UUID runId) {
        return remember(characterId, null, event, meaning, false, null, List.of(), people, sourceKind, sourceId,
                sourceLabel, runId);
    }

    public UUID remember(UUID characterId, String cue, String content, String meaning, boolean permanent, UUID aboutId,
                         List<Long> sources, List<UUID> people, String sourceKind, UUID sourceId, String sourceLabel,
                         UUID runId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO memories (id, user_id, character_id, cue, content, meaning, permanent, about_id, sources,
                    people, source_kind, source_id, source_label, run_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::bigint[], ?::uuid[], ?, ?, ?, ?)""")
                .params(id, USER, characterId, cue, content, meaning, permanent, aboutId, longArray(sources),
                        uuidArray(people), sourceKind, sourceId, sourceLabel, runId)
                .update();
        // 记忆也进亲历档案：翻找时和原话一起出现，并与它依据的原话相连
        String archived = (cue == null ? "" : cue + "\n") + content + (meaning == null ? "" : "\n" + meaning);
        long trace = traces.addMemory(characterId, id, archived);
        jdbc.sql("UPDATE memories SET trace_id = ? WHERE id = ?").params(trace, id).update();
        for (long source : sources) {
            traces.link(characterId, source, trace, false, runId);
        }
        return id;
    }

    public List<Memory> memories(UUID characterId, int limit, int offset) {
        return jdbc.sql("SELECT " + MEMORY_COLUMNS + " FROM memories WHERE character_id = ? AND user_id = ? ORDER BY created_at DESC, id LIMIT ? OFFSET ?")
                .params(characterId, USER, limit, offset).query(Inner::memory).list();
    }

    /** 按线索翻记忆：query 为空时按时间分页，否则在线索与内容里找包含它的。 */
    public List<Memory> browseMemories(UUID characterId, String query, int offset, int limit) {
        return jdbc.sql("SELECT " + MEMORY_COLUMNS + " " + """
                 FROM memories WHERE character_id = ? AND user_id = ?
                  AND (? = '' OR strpos(lower(coalesce(cue, '') || content || coalesce(meaning, '')), lower(?)) > 0)
                ORDER BY created_at DESC, id LIMIT ? OFFSET ?""")
                .params(characterId, USER, query, query, limit, offset).query(Inner::memory).list();
    }

    public int memoryCount(UUID characterId) {
        return jdbc.sql("SELECT count(*) FROM memories WHERE character_id = ? AND user_id = ?")
                .params(characterId, USER).query(Integer.class).single();
    }

    public Optional<Memory> memory(UUID id) {
        return jdbc.sql("SELECT " + MEMORY_COLUMNS + " FROM memories WHERE id = ? AND user_id = ?")
                .params(id, USER).query(Inner::memory).optional();
    }

    public Optional<Memory> memory(UUID characterId, UUID id) {
        return jdbc.sql("SELECT " + MEMORY_COLUMNS + " FROM memories WHERE character_id = ? AND id = ? AND user_id = ?")
                .params(characterId, id, USER).query(Inner::memory).optional();
    }

    public record Unindexed(UUID id, String text) {}

    public List<Unindexed> unindexedMemories(UUID characterId) {
        return jdbc.sql("""
                SELECT id, coalesce(cue || E'\n', '') || content || coalesce(E'\n' || meaning, '') AS text
                FROM memories WHERE character_id = ? AND user_id = ? AND embedding IS NULL""")
                .params(characterId, USER).query(Unindexed.class).list();
    }

    public void indexMemory(UUID id, float[] vector) {
        jdbc.sql("UPDATE memories SET embedding = ?::vector WHERE id = ?").params(Embedder.literal(vector), id).update();
    }

    /** 与查询向量最相近的记忆（余弦距离），排除 excluded。 */
    public List<Memory> similarMemories(UUID characterId, float[] query, int limit, List<UUID> excluded) {
        return similarMemories(characterId, query, limit, excluded, 2.0);
    }

    /** 同上，只要余弦距离不超过 maxDistance 的。 */
    public List<Memory> similarMemories(UUID characterId, float[] query, int limit, List<UUID> excluded,
                                        double maxDistance) {
        return jdbc.sql("SELECT " + MEMORY_COLUMNS + " " + """
                 FROM memories WHERE character_id = ? AND user_id = ? AND embedding IS NOT NULL
                  AND vector_dims(embedding) = ? AND NOT (id = ANY(?::uuid[]))
                  AND (embedding <=> ?::vector) <= ?
                ORDER BY embedding <=> ?::vector, id LIMIT ?""")
                .params(characterId, USER, query.length, uuidArray(excluded), Embedder.literal(query), maxDistance,
                        Embedder.literal(query), limit)
                .query(Inner::memory).list();
    }

    static String longArray(List<Long> ids) {
        return "{" + String.join(",", ids.stream().map(String::valueOf).toList()) + "}";
    }

    static String uuidArray(List<UUID> ids) {
        return "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}";
    }

    private static Memory memory(java.sql.ResultSet rs, int row) throws SQLException {
        Array people = rs.getArray("people");
        List<UUID> ids = people == null ? List.of() : new ArrayList<>(Arrays.asList((UUID[]) people.getArray()));
        Array sources = rs.getArray("sources");
        List<Long> traces = sources == null ? List.of() : new ArrayList<>(Arrays.asList((Long[]) sources.getArray()));
        return new Memory(rs.getObject("id", UUID.class), rs.getString("cue"), rs.getString("content"),
                rs.getString("meaning"), rs.getBoolean("permanent"), rs.getObject("about_id", UUID.class), traces,
                rs.getObject("trace_id", Long.class), ids,
                rs.getString("source_kind"), rs.getObject("source_id", UUID.class), rs.getString("source_label"),
                rs.getObject("run_id", UUID.class), rs.getTimestamp("created_at").toInstant());
    }
}
