package app.lyng.store;

import app.lyng.ApiError;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 全部表的读写。所有查询都带 user_id。 */
@Repository
public class Store {

    /** 现阶段只有一个本地用户。表结构与查询已经按多用户写。 */
    public static final String USER = "me";

    public record World(String id, String name, String publicText, String narratorText) {}

    public record Character(String id, String worldId, String name, String profile) {}

    /** 这一场还没做完的步骤：opening / narrator / 某个人物的 id / closing。round 与 action 属于正在进行的那一轮。 */
    public record Pending(int round, String action, List<String> steps) {
        public Pending next() {
            return new Pending(round, action, steps.subList(1, steps.size()));
        }
    }

    public record Scene(String id, int number, String worldId, List<String> cast, String possessedId,
                        String followId, String tone, String scale, String setup, String status,
                        Pending pending, String error, String startedAt, String endedAt) {

        public boolean ended() {
            return status.equals("ended");
        }

        /** 由 AI 驱动的在场人物：除了被附身的那一个。 */
        public List<String> ai() {
            return cast.stream().filter(id -> !id.equals(possessedId)).toList();
        }
    }

    /** 一行：narration 叙事 / say 说 / do 行 / wait 没有开口 / inner 心声。叙事没有说话人。 */
    public record Line(int seq, int round, String kind, String speakerId, boolean byUser, String text) {

        public static Line of(int round, String kind, String speakerId, boolean byUser, String text) {
            return new Line(0, round, kind, speakerId, byUser, text);
        }
    }

    public record Memory(String characterId, String sceneId, int sceneNumber, String memory, Map<String, String> views) {}

    private static final TypeReference<Map<String, String>> VIEWS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public Store(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ---- 世界与人物 ----

    public List<World> worlds() {
        return jdbc.sql("SELECT * FROM worlds WHERE user_id = ? ORDER BY created_at").param(USER).query(this::mapWorld).list();
    }

    public World world(String id) {
        return jdbc.sql("SELECT * FROM worlds WHERE user_id = ? AND id = ?").params(USER, id)
                .query(this::mapWorld).optional().orElseThrow(() -> ApiError.notFound("这个世界"));
    }

    public boolean worldNameTaken(String name) {
        return jdbc.sql("SELECT COUNT(*) FROM worlds WHERE user_id = ? AND name = ?").params(USER, name)
                .query(Integer.class).single() > 0;
    }

    public World insertWorld(String name, String publicText, String narratorText) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO worlds (id, user_id, name, public_text, narrator_text) VALUES (?, ?, ?, ?, ?)")
                .params(id, USER, name, publicText, narratorText).update();
        return new World(id, name, publicText, narratorText);
    }

    public List<Character> characters() {
        return jdbc.sql("SELECT * FROM characters WHERE user_id = ? ORDER BY created_at").param(USER)
                .query(this::mapCharacter).list();
    }

    public List<Character> charactersOf(String worldId) {
        return jdbc.sql("SELECT * FROM characters WHERE user_id = ? AND world_id = ? ORDER BY created_at")
                .params(USER, worldId).query(this::mapCharacter).list();
    }

    public Character character(String id) {
        return jdbc.sql("SELECT * FROM characters WHERE user_id = ? AND id = ?").params(USER, id)
                .query(this::mapCharacter).optional().orElseThrow(() -> ApiError.notFound("这个人物"));
    }

    public Character insertCharacter(String worldId, String name, String profile) {
        String id = UUID.randomUUID().toString();
        jdbc.sql("INSERT INTO characters (id, user_id, world_id, name, profile) VALUES (?, ?, ?, ?, ?)")
                .params(id, USER, worldId, name, profile).update();
        return new Character(id, worldId, name, profile);
    }

    // ---- 场景 ----

    public List<Scene> scenes() {
        return jdbc.sql("SELECT * FROM scenes WHERE user_id = ? ORDER BY number DESC").param(USER).query(this::mapScene).list();
    }

    public Scene scene(String id) {
        return jdbc.sql("SELECT * FROM scenes WHERE user_id = ? AND id = ?").params(USER, id)
                .query(this::mapScene).optional().orElseThrow(() -> ApiError.notFound("这一场戏"));
    }

    public Scene insertScene(String worldId, List<String> cast, String possessedId, String followId, String tone,
                             String scale, String setup, Pending pending) {
        String id = UUID.randomUUID().toString();
        int number = jdbc.sql("SELECT COALESCE(MAX(number), 0) + 1 FROM scenes WHERE user_id = ?").param(USER)
                .query(Integer.class).single();
        jdbc.sql("""
                INSERT INTO scenes (id, user_id, number, world_id, cast_ids, possessed_id, follow_id, tone, scale, setup,
                                    status, pending)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'open', ?)""")
                .params(id, USER, number, worldId, String.join(",", cast), possessedId, followId, tone, scale, setup,
                        json.writeValueAsString(pending))
                .update();
        return scene(id);
    }

    public void setIdentity(String sceneId, String possessedId, String followId) {
        jdbc.sql("UPDATE scenes SET possessed_id = ?, follow_id = ? WHERE user_id = ? AND id = ?")
                .params(possessedId, followId, USER, sceneId).update();
    }

    /** pending 为 null 表示这一场没有未完成的步骤。 */
    public void setPending(String sceneId, Pending pending, String error) {
        jdbc.sql("UPDATE scenes SET pending = ?, error = ? WHERE user_id = ? AND id = ?")
                .params(pending == null ? null : json.writeValueAsString(pending), error, USER, sceneId).update();
    }

    public void end(String sceneId) {
        jdbc.sql("""
                UPDATE scenes SET status = 'ended', pending = NULL, error = NULL,
                                  ended_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
                WHERE user_id = ? AND id = ?""").params(USER, sceneId).update();
    }

    /** 这个人物在场、已经结束的场景，由旧到新。 */
    public List<Scene> endedScenesOf(String characterId) {
        return jdbc.sql("""
                SELECT * FROM scenes
                WHERE user_id = ? AND status = 'ended' AND (',' || cast_ids || ',') LIKE ?
                ORDER BY number""").params(USER, "%," + characterId + ",%").query(this::mapScene).list();
    }

    public List<Scene> scenesOf(String characterId) {
        return jdbc.sql("SELECT * FROM scenes WHERE user_id = ? AND (',' || cast_ids || ',') LIKE ? ORDER BY number DESC")
                .params(USER, "%," + characterId + ",%").query(this::mapScene).list();
    }

    public Scene latestEndedInWorld(String worldId, String exceptSceneId) {
        return jdbc.sql("""
                SELECT * FROM scenes WHERE user_id = ? AND world_id = ? AND status = 'ended' AND id <> ?
                ORDER BY number DESC LIMIT 1""").params(USER, worldId, exceptSceneId).query(this::mapScene).optional().orElse(null);
    }

    // ---- 一场戏里发生的事 ----

    public List<Line> lines(String sceneId) {
        return jdbc.sql("SELECT * FROM lines WHERE user_id = ? AND scene_id = ? ORDER BY seq").params(USER, sceneId)
                .query((rs, n) -> new Line(rs.getInt("seq"), rs.getInt("round"), rs.getString("kind"),
                        rs.getString("speaker_id"), rs.getInt("by_user") == 1, rs.getString("text")))
                .list();
    }

    /** 追加若干行，返回带序号的行。调用方持有场锁。 */
    public List<Line> append(String sceneId, List<Line> lines) {
        int seq = jdbc.sql("SELECT COALESCE(MAX(seq), 0) FROM lines WHERE scene_id = ?").param(sceneId)
                .query(Integer.class).single();
        List<Line> saved = new ArrayList<>();
        for (Line l : lines) {
            Line line = new Line(++seq, l.round(), l.kind(), l.speakerId(), l.byUser(), l.text());
            jdbc.sql("""
                    INSERT INTO lines (scene_id, seq, user_id, round, kind, speaker_id, by_user, text)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(sceneId, line.seq(), USER, line.round(), line.kind(), line.speakerId(),
                            line.byUser() ? 1 : 0, line.text())
                    .update();
            saved.add(line);
        }
        return saved;
    }

    /** 每场最后一句说出口的话或叙事，用作场景列表里的预览。 */
    public Map<String, String> previews() {
        return jdbc.sql("""
                SELECT l.scene_id, l.text FROM lines l
                WHERE l.user_id = ? AND l.seq = (SELECT MAX(seq) FROM lines x
                                                 WHERE x.scene_id = l.scene_id AND x.kind IN ('say', 'do', 'narration'))""")
                .param(USER).query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2))).list().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    // ---- 回想 ----

    /** 这个人物的全部回想，按场景先后。 */
    public List<Memory> memories(String characterId) {
        return jdbc.sql("""
                SELECT m.*, s.number FROM memories m JOIN scenes s ON s.id = m.scene_id
                WHERE m.user_id = ? AND m.character_id = ? ORDER BY s.number""")
                .params(USER, characterId)
                .query((rs, n) -> new Memory(rs.getString("character_id"), rs.getString("scene_id"),
                        rs.getInt("number"), rs.getString("memory"), json.readValue(rs.getString("views"), VIEWS)))
                .list();
    }

    public void saveMemory(String characterId, String sceneId, String memory, Map<String, String> views) {
        jdbc.sql("""
                INSERT INTO memories (character_id, scene_id, user_id, memory, views) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (character_id, scene_id) DO UPDATE SET memory = excluded.memory, views = excluded.views""")
                .params(characterId, sceneId, USER, memory, json.writeValueAsString(views)).update();
    }

    // ---- 用量 ----

    public record Call(String createdAt, String kind, String subject, String model, String provider, String outcome,
                       String error, Double cost, Integer latencyMs, Integer reasoningTokens) {}

    public List<Call> recentCalls(int limit) {
        return jdbc.sql("SELECT * FROM model_calls WHERE user_id = ? ORDER BY id DESC LIMIT ?").params(USER, limit)
                .query((rs, n) -> new Call(rs.getString("created_at"), rs.getString("kind"), rs.getString("subject"),
                        rs.getString("model"), rs.getString("provider"), rs.getString("outcome"), rs.getString("error"),
                        number(rs, "cost") == null ? null : number(rs, "cost").doubleValue(),
                        number(rs, "latency_ms") == null ? null : number(rs, "latency_ms").intValue(),
                        number(rs, "reasoning_tokens") == null ? null : number(rs, "reasoning_tokens").intValue()))
                .list();
    }

    // ---- 映射 ----

    private static Number number(ResultSet rs, String column) throws SQLException {
        return (Number) rs.getObject(column);
    }

    private World mapWorld(ResultSet rs, int n) throws SQLException {
        return new World(rs.getString("id"), rs.getString("name"), rs.getString("public_text"), rs.getString("narrator_text"));
    }

    private Character mapCharacter(ResultSet rs, int n) throws SQLException {
        return new Character(rs.getString("id"), rs.getString("world_id"), rs.getString("name"), rs.getString("profile"));
    }

    private Scene mapScene(ResultSet rs, int n) throws SQLException {
        String pending = rs.getString("pending");
        return new Scene(rs.getString("id"), rs.getInt("number"), rs.getString("world_id"),
                Arrays.asList(rs.getString("cast_ids").split(",")), rs.getString("possessed_id"),
                rs.getString("follow_id"), rs.getString("tone"), rs.getString("scale"), rs.getString("setup"),
                rs.getString("status"), pending == null ? null : json.readValue(pending, Pending.class),
                rs.getString("error"), rs.getString("started_at"), rs.getString("ended_at"));
    }
}
