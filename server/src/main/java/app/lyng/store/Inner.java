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

/** 人物的内心：如今的自我、看法、牵挂、记忆。只有人物自己（经整理或诞生）写入；用户只能看。 */
@Repository
public class Inner {

    public record Self(String text, UUID runId, Instant createdAt) {}

    public record View(UUID otherId, String text, UUID runId, Instant createdAt) {}

    public record Concern(UUID id, UUID aboutId, String text, String status, String resolution, UUID runId,
                          Instant createdAt, Instant updatedAt) {}

    public record Memory(UUID id, String event, String meaning, List<UUID> people, String sourceKind, UUID sourceId,
                         String sourceLabel, UUID runId, Instant createdAt) {}

    private final JdbcClient jdbc;

    public Inner(JdbcClient jdbc) {
        this.jdbc = jdbc;
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

    private static final String CONCERN_COLUMNS = "id, about_id, text, status, resolution, run_id, created_at, updated_at";

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

    public UUID addConcern(UUID characterId, UUID aboutId, String text, UUID runId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO concerns (id, user_id, character_id, about_id, text, status, run_id) VALUES (?, ?, ?, ?, ?, 'active', ?)")
                .params(id, USER, characterId, aboutId, text, runId).update();
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
            "id, event, meaning, people, source_kind, source_id, source_label, run_id, created_at";

    public UUID addMemory(UUID characterId, String event, String meaning, List<UUID> people, String sourceKind,
                          UUID sourceId, String sourceLabel, UUID runId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO memories (id, user_id, character_id, event, meaning, people, source_kind, source_id,
                    source_label, run_id)
                VALUES (?, ?, ?, ?, ?, ?::uuid[], ?, ?, ?, ?)""")
                .params(id, USER, characterId, event, meaning, uuidArray(people), sourceKind, sourceId, sourceLabel, runId)
                .update();
        return id;
    }

    public List<Memory> memories(UUID characterId, int limit, int offset) {
        return jdbc.sql("SELECT " + MEMORY_COLUMNS + " FROM memories WHERE character_id = ? AND user_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?")
                .params(characterId, USER, limit, offset).query(Inner::memory).list();
    }

    public int memoryCount(UUID characterId) {
        return jdbc.sql("SELECT count(*) FROM memories WHERE character_id = ? AND user_id = ?")
                .params(characterId, USER).query(Integer.class).single();
    }

    public Optional<Memory> memory(UUID id) {
        return jdbc.sql("SELECT " + MEMORY_COLUMNS + " FROM memories WHERE id = ? AND user_id = ?")
                .params(id, USER).query(Inner::memory).optional();
    }

    public record Unindexed(UUID id, String text) {}

    public List<Unindexed> unindexedMemories(UUID characterId) {
        return jdbc.sql("SELECT id, event || '\n' || meaning AS text FROM memories WHERE character_id = ? AND user_id = ? AND embedding IS NULL")
                .params(characterId, USER).query(Unindexed.class).list();
    }

    public void indexMemory(UUID id, float[] vector) {
        jdbc.sql("UPDATE memories SET embedding = ?::vector WHERE id = ?").params(Embedder.literal(vector), id).update();
    }

    /** 与查询向量最相近的记忆（余弦距离），排除 excluded。 */
    public List<Memory> similarMemories(UUID characterId, float[] query, int limit, List<UUID> excluded) {
        return jdbc.sql("SELECT " + MEMORY_COLUMNS + " " + """
                 FROM memories WHERE character_id = ? AND user_id = ? AND embedding IS NOT NULL
                  AND vector_dims(embedding) = ? AND NOT (id = ANY(?::uuid[]))
                ORDER BY embedding <=> ?::vector LIMIT ?""")
                .params(characterId, USER, query.length, uuidArray(excluded), Embedder.literal(query), limit)
                .query(Inner::memory).list();
    }

    static String uuidArray(List<UUID> ids) {
        return "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}";
    }

    private static Memory memory(java.sql.ResultSet rs, int row) throws SQLException {
        Array people = rs.getArray("people");
        List<UUID> ids = people == null ? List.of() : new ArrayList<>(Arrays.asList((UUID[]) people.getArray()));
        return new Memory(rs.getObject("id", UUID.class), rs.getString("event"), rs.getString("meaning"), ids,
                rs.getString("source_kind"), rs.getObject("source_id", UUID.class), rs.getString("source_label"),
                rs.getObject("run_id", UUID.class), rs.getTimestamp("created_at").toInstant());
    }
}
