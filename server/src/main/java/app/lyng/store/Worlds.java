package app.lyng.store;

import static app.lyng.LyngApplication.USER;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 世界与人物（出生档案）。诞生后不改。 */
@Repository
public class Worlds {

    public record World(UUID id, String name, String publicText, String narratorText, Instant createdAt) {}

    public record Character(UUID id, UUID worldId, String name, String profile, Instant createdAt) {}

    private final JdbcClient jdbc;

    public Worlds(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID createWorld(String name, String publicText, String narratorText) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO worlds (id, user_id, name, public_text, narrator_text) VALUES (?, ?, ?, ?, ?)")
                .params(id, USER, name, publicText, narratorText).update();
        return id;
    }

    public UUID createCharacter(UUID worldId, String name, String profile) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO characters (id, user_id, world_id, name, profile) VALUES (?, ?, ?, ?, ?)")
                .params(id, USER, worldId, name, profile).update();
        return id;
    }

    public List<World> worlds() {
        return jdbc.sql("SELECT id, name, public_text, narrator_text, created_at FROM worlds WHERE user_id = ? ORDER BY created_at")
                .param(USER).query(World.class).list();
    }

    public Optional<World> world(UUID id) {
        return jdbc.sql("SELECT id, name, public_text, narrator_text, created_at FROM worlds WHERE id = ? AND user_id = ?")
                .params(id, USER).query(World.class).optional();
    }

    public World requireWorld(UUID id) {
        return world(id).orElseThrow(() -> new NotFound("没有这个世界"));
    }

    public List<Character> characters(UUID worldId) {
        return jdbc.sql("""
                SELECT id, world_id, name, profile, created_at FROM characters
                WHERE world_id = ? AND user_id = ? ORDER BY created_at, name""")
                .params(worldId, USER).query(Character.class).list();
    }

    public List<Character> allCharacters() {
        return jdbc.sql("SELECT id, world_id, name, profile, created_at FROM characters WHERE user_id = ? ORDER BY created_at, name")
                .param(USER).query(Character.class).list();
    }

    public Optional<Character> character(UUID id) {
        return jdbc.sql("SELECT id, world_id, name, profile, created_at FROM characters WHERE id = ? AND user_id = ?")
                .params(id, USER).query(Character.class).optional();
    }

    public Character requireCharacter(UUID id) {
        return character(id).orElseThrow(() -> new NotFound("没有这个人物"));
    }

    public List<Character> characters(List<UUID> ids) {
        return ids.stream().map(this::requireCharacter).toList();
    }
}
