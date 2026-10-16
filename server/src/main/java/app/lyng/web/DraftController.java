package app.lyng.web;

import app.lyng.create.CreationService;
import app.lyng.create.CreationService.Born;
import app.lyng.create.CreationService.Page;
import app.lyng.create.CreationService.Revision;
import app.lyng.mind.Markup.Draft;
import app.lyng.mind.Prompts.Answer;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 一念生。 */
@RestController
@RequestMapping("/api/drafts")
public class DraftController {

    public record Start(UUID worldId, String request) {}

    public record Write(List<Answer> answers) {}

    public record Revise(String target, String instruction) {}

    private final CreationService service;
    private final Streams streams;

    public DraftController(CreationService service, Streams streams) {
        this.service = service;
        this.streams = streams;
    }

    @GetMapping
    public List<Page> unfinished() {
        return service.unfinished();
    }

    @PostMapping
    public Page start(@RequestBody Start body) {
        return service.start(body.worldId(), body.request());
    }

    @GetMapping("/{id}")
    public Page page(@PathVariable UUID id) {
        return service.page(id);
    }

    @PostMapping("/{id}/ask")
    public Page ask(@PathVariable UUID id) {
        return service.ask(id);
    }

    @PostMapping("/{id}/write")
    public SseEmitter write(@PathVariable UUID id, @RequestBody(required = false) Write body) {
        return streams.run(events -> service.write(id, body == null ? List.of() : body.answers(), events));
    }

    @PutMapping("/{id}")
    public Page edit(@PathVariable UUID id, @RequestBody Draft content) {
        return service.edit(id, content);
    }

    @PostMapping("/{id}/revise")
    public Revision revise(@PathVariable UUID id, @RequestBody Revise body) {
        return service.revise(id, body.target(), body.instruction());
    }

    @PostMapping("/{id}/check")
    public Page check(@PathVariable UUID id) {
        return service.check(id);
    }

    @PostMapping("/{id}/birth")
    public Born birth(@PathVariable UUID id) {
        return service.birth(id);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}
