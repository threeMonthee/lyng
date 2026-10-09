package app.lyng.world;

import app.lyng.web.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WorldRepository {

    private static final String WORLD = "SELECT id, name, public_text, narrator_text FROM worlds";
    private static final String CHARACTER = "SELECT id, home_world_id, name, profile FROM characters";

    private final JdbcClient jdbc;

    public WorldRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public World insertWorld(UUID userId, String name, String publicText, String narratorText) {
        World world = new World(UUID.randomUUID(), name, publicText, narratorText);
        jdbc.sql("INSERT INTO worlds (id, user_id, name, public_text, narrator_text) VALUES (?, ?, ?, ?, ?)")
                .params(world.id(), userId, name, publicText, narratorText).update();
        return world;
    }

    public Character insertCharacter(UUID userId, UUID worldId, String name, String profile) {
        Character character = new Character(UUID.randomUUID(), worldId, name, profile);
        jdbc.sql("INSERT INTO characters (id, user_id, home_world_id, name, profile) VALUES (?, ?, ?, ?, ?)")
                .params(character.id(), userId, worldId, name, profile).update();
        return character;
    }

    public List<World> worlds(UUID userId) {
        return jdbc.sql(WORLD + " WHERE user_id = ? ORDER BY created_at").param(userId).query(World.class).list();
    }

    public World world(UUID userId, UUID id) {
        return jdbc.sql(WORLD + " WHERE user_id = ? AND id = ?").params(userId, id).query(World.class).optional()
                .orElseThrow(() -> new NotFoundException("世界"));
    }

    public List<Character> characters(UUID userId) {
        return jdbc.sql(CHARACTER + " WHERE user_id = ? ORDER BY created_at, name").param(userId)
                .query(Character.class).list();
    }

    public List<Character> charactersOf(UUID userId, UUID worldId) {
        return jdbc.sql(CHARACTER + " WHERE user_id = ? AND home_world_id = ? ORDER BY created_at, name")
                .params(userId, worldId).query(Character.class).list();
    }

    public Character character(UUID userId, UUID id) {
        return jdbc.sql(CHARACTER + " WHERE user_id = ? AND id = ?").params(userId, id).query(Character.class)
                .optional().orElseThrow(() -> new NotFoundException("人物"));
    }
}
