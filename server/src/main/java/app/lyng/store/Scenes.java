package app.lyng.store;

import static app.lyng.LyngApplication.USER;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 一场戏：场景、在场人物、发生的每一行。行只追加。 */
@Repository
public class Scenes {

    public record Scene(UUID id, UUID worldId, int number, String tone, String scale, String setup, UUID possessedId,
                        UUID followId, String status, List<String> pending, String failure, int round,
                        Instant createdAt, Instant closedAt) {

        public boolean open() {
            return status.equals("open");
        }
    }

    public record Line(long id, int round, String kind, UUID speakerId, String text, boolean byUser, UUID runId,
                       Instant createdAt) {}

    private final JdbcClient jdbc;

    public Scenes(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS = """
            id, world_id, number, tone, scale, setup, possessed_id, follow_id, status, pending, failure, round,
            created_at, closed_at""";

    public UUID create(UUID worldId, String tone, String scale, String setup, UUID possessedId, UUID followId,
                       List<UUID> cast) {
        UUID id = UUID.randomUUID();
        int number = jdbc.sql("SELECT COALESCE(MAX(number), 0) + 1 FROM scenes WHERE world_id = ? AND user_id = ?")
                .params(worldId, USER).query(Integer.class).single();
        jdbc.sql("""
                INSERT INTO scenes (id, user_id, world_id, number, tone, scale, setup, possessed_id, follow_id, status, pending)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'open', '{opening}')""")
                .params(id, USER, worldId, number, tone, scale, setup, possessedId, followId).update();
        for (int i = 0; i < cast.size(); i++) {
            jdbc.sql("INSERT INTO scene_cast (scene_id, character_id, position) VALUES (?, ?, ?)")
                    .params(id, cast.get(i), i).update();
        }
        return id;
    }

    public Optional<Scene> scene(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM scenes WHERE id = ? AND user_id = ?")
                .params(id, USER).query(Scenes::scene).optional();
    }

    public Scene require(UUID id) {
        return scene(id).orElseThrow(() -> new NotFound("没有这一场戏"));
    }

    public List<Scene> byWorld(UUID worldId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM scenes WHERE world_id = ? AND user_id = ? ORDER BY number DESC")
                .params(worldId, USER).query(Scenes::scene).list();
    }

    public List<Scene> recent(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM scenes WHERE user_id = ? ORDER BY created_at DESC LIMIT ?")
                .params(USER, limit).query(Scenes::scene).list();
    }

    /** 这个世界里上一场（已结束的、序号小于 number 的最后一场）。 */
    public Optional<Scene> previous(UUID worldId, int number) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM scenes WHERE world_id = ? AND user_id = ? AND number < ? AND status = 'closed' ORDER BY number DESC LIMIT 1")
                .params(worldId, USER, number).query(Scenes::scene).optional();
    }

    /** 这个人物正在其中的、未结束的戏。 */
    public List<Scene> openWith(UUID characterId) {
        return jdbc.sql("SELECT " + COLUMNS + " " + """
                 FROM scenes WHERE user_id = ? AND status = 'open'
                  AND id IN (SELECT scene_id FROM scene_cast WHERE character_id = ?)""")
                .params(USER, characterId).query(Scenes::scene).list();
    }

    public List<UUID> cast(UUID sceneId) {
        return jdbc.sql("SELECT character_id FROM scene_cast WHERE scene_id = ? ORDER BY position")
                .param(sceneId).query(UUID.class).list();
    }

    /** 和这个人物同场过的人。 */
    public List<UUID> castmates(UUID characterId) {
        return jdbc.sql("""
                SELECT b.character_id FROM scene_cast a JOIN scene_cast b ON b.scene_id = a.scene_id
                JOIN scenes s ON s.id = a.scene_id
                WHERE a.character_id = ? AND b.character_id <> ? AND s.user_id = ?
                GROUP BY b.character_id ORDER BY min(s.created_at)""")
                .params(characterId, characterId, USER).query(UUID.class).list();
    }

    public List<Line> lines(UUID sceneId) {
        return jdbc.sql("SELECT id, round, kind, speaker_id, text, by_user, run_id, created_at FROM lines WHERE scene_id = ? AND user_id = ? ORDER BY id")
                .params(sceneId, USER).query(Line.class).list();
    }

    public long addLine(UUID sceneId, int round, String kind, UUID speakerId, String text, boolean byUser, UUID runId) {
        return jdbc.sql("""
                INSERT INTO lines (user_id, scene_id, round, kind, speaker_id, text, by_user, run_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")
                .params(USER, sceneId, round, kind, speakerId, text, byUser, runId).query(Long.class).single();
    }

    public void setPending(UUID sceneId, List<String> pending, String failure) {
        jdbc.sql("UPDATE scenes SET pending = ?::text[], failure = ? WHERE id = ? AND user_id = ?")
                .params(textArray(pending), failure, sceneId, USER).update();
    }

    public void setRound(UUID sceneId, int round) {
        jdbc.sql("UPDATE scenes SET round = ? WHERE id = ? AND user_id = ?").params(round, sceneId, USER).update();
    }

    public void setIdentity(UUID sceneId, UUID possessedId, UUID followId) {
        jdbc.sql("UPDATE scenes SET possessed_id = ?, follow_id = ? WHERE id = ? AND user_id = ?")
                .params(possessedId, followId, sceneId, USER).update();
    }

    public void close(UUID sceneId) {
        jdbc.sql("UPDATE scenes SET status = 'closed', pending = '{}', failure = NULL, closed_at = now() WHERE id = ? AND user_id = ?")
                .params(sceneId, USER).update();
    }

    static String textArray(List<String> values) {
        return "{" + String.join(",", values.stream().map(v -> "\"" + v.replace("\"", "\\\"") + "\"").toList()) + "}";
    }

    private static Scene scene(ResultSet rs, int row) throws SQLException {
        Array pending = rs.getArray("pending");
        Instant closed = rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant();
        return new Scene(rs.getObject("id", UUID.class), rs.getObject("world_id", UUID.class), rs.getInt("number"),
                rs.getString("tone"), rs.getString("scale"), rs.getString("setup"),
                rs.getObject("possessed_id", UUID.class), rs.getObject("follow_id", UUID.class), rs.getString("status"),
                pending == null ? List.of() : Arrays.asList((String[]) pending.getArray()), rs.getString("failure"),
                rs.getInt("round"), rs.getTimestamp("created_at").toInstant(), closed);
    }
}
