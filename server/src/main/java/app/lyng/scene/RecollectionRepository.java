package app.lyng.scene;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class RecollectionRepository {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public RecollectionRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void save(UUID userId, Recollection r) {
        jdbc.sql("""
                INSERT INTO recollections (id, user_id, character_id, scene_id, status, memory, views, error)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (character_id, scene_id) DO UPDATE
                SET status = EXCLUDED.status, memory = EXCLUDED.memory, views = EXCLUDED.views, error = EXCLUDED.error""")
                .params(UUID.randomUUID(), userId, r.characterId(), r.sceneId(), r.status(), r.memory(),
                        json.writeValueAsString(r.views()), r.error())
                .update();
    }

    /** 这个人物的全部回想，按场次由旧到新。 */
    public List<Recollection> ofCharacter(UUID userId, UUID characterId) {
        return jdbc.sql("""
                SELECT r.character_id, r.scene_id, r.status, r.memory, r.views, r.error
                FROM recollections r JOIN scenes s ON s.id = r.scene_id
                WHERE r.user_id = ? AND r.character_id = ? ORDER BY s.number""")
                .params(userId, characterId).query(this::recollection).list();
    }

    private Recollection recollection(ResultSet rs, int row) throws SQLException {
        Map<UUID, String> views = json.readValue(rs.getString("views"), new TypeReference<Map<UUID, String>>() {});
        return new Recollection(rs.getObject("character_id", UUID.class), rs.getObject("scene_id", UUID.class),
                rs.getString("status"), rs.getString("memory"), views, rs.getString("error"));
    }
}
