package app.lyng.web;

import app.lyng.auth.AuthFilter;
import app.lyng.scene.RecallService;
import app.lyng.world.Character;
import app.lyng.world.World;
import app.lyng.world.WorldRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class WorldController {

    record WorldView(UUID id, String name, String publicText, List<CharacterSummary> characters) {}

    record CharacterSummary(UUID id, String name) {}

    record CharacterView(UUID id, UUID worldId, String worldName, String name, String profile,
                         RecallService.CharacterMemory memory) {}

    private final WorldRepository worlds;
    private final RecallService recalls;

    WorldController(WorldRepository worlds, RecallService recalls) {
        this.worlds = worlds;
        this.recalls = recalls;
    }

    @GetMapping("/worlds")
    List<WorldView> worlds(@RequestAttribute(AuthFilter.USER) UUID userId) {
        return worlds.worlds(userId).stream().map(w -> new WorldView(w.id(), w.name(), w.publicText(),
                worlds.charactersOf(userId, w.id()).stream().map(c -> new CharacterSummary(c.id(), c.name())).toList())).toList();
    }

    /** 人物页：档案，以及 TA 记得的事、TA 怎么看某人（只读）。 */
    @GetMapping("/characters/{id}")
    CharacterView character(@RequestAttribute(AuthFilter.USER) UUID userId, @PathVariable UUID id) {
        Character c = worlds.character(userId, id);
        World world = worlds.world(userId, c.homeWorldId());
        return new CharacterView(c.id(), world.id(), world.name(), c.name(), c.profile(), recalls.memoryOf(userId, id));
    }
}
