package app.lyng.scene;

import app.lyng.event.EventKind;
import app.lyng.event.SceneEvent;
import app.lyng.markup.Beat;
import app.lyng.web.NotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class SceneRepository {

    private static final String SCENE = """
            SELECT id, number, world_id, possessed_id, tone, scale, setup, status, started_at, ended_at FROM scenes""";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public SceneRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional
    public Scene insert(UUID userId, UUID worldId, UUID possessedId, String tone, String scale, String setup,
                        List<UUID> characterIds) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO scenes (id, user_id, number, world_id, possessed_id, tone, scale, setup, status)
                VALUES (?, ?, (SELECT COALESCE(MAX(number), 0) + 1 FROM scenes WHERE user_id = ?), ?, ?, ?, ?, ?, 'open')""")
                .params(id, userId, userId, worldId, possessedId, tone, scale, setup).update();
        for (UUID characterId : characterIds) {
            jdbc.sql("INSERT INTO scene_characters (user_id, scene_id, character_id) VALUES (?, ?, ?)")
                    .params(userId, id, characterId).update();
        }
        return scene(userId, id);
    }

    public Scene scene(UUID userId, UUID id) {
        return jdbc.sql(SCENE + " WHERE user_id = ? AND id = ?").params(userId, id).query(Scene.class).optional()
                .orElseThrow(() -> new NotFoundException("这一场戏"));
    }

    public List<Scene> scenes(UUID userId) {
        return jdbc.sql(SCENE + " WHERE user_id = ? ORDER BY number DESC").param(userId).query(Scene.class).list();
    }

    public List<UUID> characterIds(UUID userId, UUID sceneId) {
        return jdbc.sql("SELECT character_id FROM scene_characters WHERE user_id = ? AND scene_id = ? ORDER BY character_id")
                .params(userId, sceneId).query(UUID.class).list();
    }

    /** 这个人物经历过的、已结束的场景，由旧到新。 */
    public List<Scene> endedScenesOf(UUID userId, UUID characterId) {
        return jdbc.sql("""
                SELECT s.id, s.number, s.world_id, s.possessed_id, s.tone, s.scale, s.setup, s.status, s.started_at, s.ended_at
                FROM scenes s JOIN scene_characters sc ON sc.scene_id = s.id
                WHERE s.user_id = ? AND sc.character_id = ? AND s.status = 'ended' ORDER BY s.number""")
                .params(userId, characterId).query(Scene.class).list();
    }

    /** 同一个世界里最近一场已结束的戏，叙事者接上它。 */
    public Scene latestEndedInWorld(UUID userId, UUID worldId) {
        return jdbc.sql(SCENE + " WHERE user_id = ? AND world_id = ? AND status = 'ended' ORDER BY number DESC LIMIT 1")
                .params(userId, worldId).query(Scene.class).optional().orElse(null);
    }

    public void end(UUID userId, UUID sceneId) {
        jdbc.sql("UPDATE scenes SET status = 'ended', ended_at = now() WHERE user_id = ? AND id = ?")
                .params(userId, sceneId).update();
    }

    public List<SceneEvent> events(UUID userId, UUID sceneId) {
        return jdbc.sql("""
                SELECT e.seq, e.round, e.kind, e.speaker_id, c.name AS speaker, e.action, e.text, e.beats, e.inner_voice AS inner,
                       e.silent, e.step, e.ref_seq
                FROM scene_events e LEFT JOIN characters c ON c.id = e.speaker_id
                WHERE e.user_id = ? AND e.scene_id = ? ORDER BY e.seq""")
                .params(userId, sceneId).query(this::event).list();
    }

    /** 追加一件事，返回带 seq 的事件。场内同一时刻只有一个写入者（场锁），seq 靠唯一约束兜底。 */
    public SceneEvent append(UUID userId, UUID sceneId, SceneEvent e) {
        int seq = jdbc.sql("""
                INSERT INTO scene_events (user_id, scene_id, seq, round, kind, speaker_id, action, text, beats, inner_voice, silent, step, ref_seq)
                VALUES (?, ?, (SELECT COALESCE(MAX(seq), 0) + 1 FROM scene_events WHERE scene_id = ?), ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                RETURNING seq""")
                .params(userId, sceneId, sceneId, e.round(), e.kind().name().toLowerCase(), e.speakerId(), e.action(),
                        e.text(), json.writeValueAsString(e.beats()), e.inner(), e.silent(), e.step(), e.refSeq())
                .query(Integer.class).single();
        return e.withSeq(seq);
    }

    private SceneEvent event(ResultSet rs, int row) throws SQLException {
        List<Beat> beats = json.readValue(rs.getString("beats"), new TypeReference<List<Beat>>() {});
        return new SceneEvent(rs.getInt("seq"), rs.getInt("round"), EventKind.valueOf(rs.getString("kind").toUpperCase()),
                rs.getObject("speaker_id", UUID.class), rs.getString("speaker"), rs.getString("action"),
                rs.getString("text"), beats, rs.getString("inner"), rs.getBoolean("silent"), rs.getString("step"),
                (Integer) rs.getObject("ref_seq"));
    }
}
