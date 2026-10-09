package app.lyng.web;

import app.lyng.auth.AuthFilter;
import app.lyng.markup.Draft;
import app.lyng.world.Character;
import app.lyng.world.CreationService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/creation")
class CreationController {

    record DraftRequest(String request, UUID worldId) {}

    record ConfirmRequest(UUID worldId, Draft draft) {}

    record Born(UUID worldId, List<Born.Person> characters) {
        record Person(UUID id, String name) {}
    }

    private final CreationService creation;

    CreationController(CreationService creation) {
        this.creation = creation;
    }

    @PostMapping("/draft")
    Draft draft(@RequestAttribute(AuthFilter.USER) UUID userId, @RequestBody DraftRequest body) {
        return creation.draft(userId, body.request(), body.worldId());
    }

    @PostMapping("/confirm")
    Born confirm(@RequestAttribute(AuthFilter.USER) UUID userId, @RequestBody ConfirmRequest body) {
        List<Character> born = creation.confirm(userId, body.draft(), body.worldId());
        return new Born(born.getFirst().homeWorldId(), born.stream().map(c -> new Born.Person(c.id(), c.name())).toList());
    }
}
