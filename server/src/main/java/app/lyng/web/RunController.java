package app.lyng.web;

import app.lyng.mind.Prompts;
import app.lyng.model.Gateway;
import app.lyng.model.Purpose;
import app.lyng.store.NotFound;
import app.lyng.store.Runs;
import app.lyng.store.Settings;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 幕后（每次运行的完整记录）、用量与设置。 */
@RestController
@RequestMapping("/api")
public class RunController {

    public record RunItem(UUID id, String purpose, String purposeLabel, String title, UUID characterId, UUID sceneId,
                          UUID conversationId, UUID draftId, String status, String failure, int attempts,
                          BigDecimal cost, Instant startedAt, Instant finishedAt) {}

    public record RunPage(RunItem run, JsonNode request, String output, String reasoning, JsonNode tools,
                          JsonNode outcome, List<Runs.Call> calls) {}

    public record Usage(double spent, Double budget, List<Runs.Usage> purposes, List<Runs.Day> days) {}

    public record SettingsBody(String scale, BigDecimal budgetUsd) {}

    private final Runs runs;
    private final Settings settings;
    private final Gateway gateway;
    private final JsonMapper json;

    public RunController(Runs runs, Settings settings, Gateway gateway, JsonMapper json) {
        this.runs = runs;
        this.settings = settings;
        this.gateway = gateway;
        this.json = json;
    }

    @GetMapping("/runs")
    public List<RunItem> list(@RequestParam(required = false) UUID characterId,
                              @RequestParam(required = false) UUID sceneId,
                              @RequestParam(required = false) UUID conversationId,
                              @RequestParam(defaultValue = "0") int offset,
                              @RequestParam(defaultValue = "30") int limit) {
        return runs.list(characterId, sceneId, conversationId, Math.min(limit, 100), Math.max(offset, 0)).stream()
                .map(r -> new RunItem(r.id(), r.purpose(), label(r.purpose()), r.title(), r.characterId(), r.sceneId(),
                        r.conversationId(), r.draftId(), r.status(), r.failure(), r.attempts(), r.cost(),
                        r.startedAt(), r.finishedAt()))
                .toList();
    }

    @GetMapping("/runs/{id}")
    public RunPage run(@PathVariable UUID id) {
        Runs.Detail d = runs.detail(id).orElseThrow(() -> new NotFound("没有这次运行"));
        RunItem item = new RunItem(d.id(), d.purpose(), label(d.purpose()), d.title(), d.characterId(), d.sceneId(),
                d.conversationId(), d.draftId(), d.status(), d.failure(), d.attempts(), d.cost(), d.startedAt(),
                d.finishedAt());
        return new RunPage(item, tree(d.request()), d.output(), d.reasoning(), tree(d.tools()), tree(d.outcome()),
                runs.calls(id));
    }

    @GetMapping("/usage")
    public Usage usage() {
        return new Usage(gateway.spent(), gateway.budget(), runs.usage(), runs.days(14));
    }

    @GetMapping("/settings")
    public Settings.Values settings() {
        return settings.get();
    }

    @PutMapping("/settings")
    public Settings.Values update(@RequestBody SettingsBody body) {
        if (!Prompts.MILD.equals(body.scale()) && !Prompts.ADULT.equals(body.scale())) {
            throw new IllegalArgumentException("内容尺度只有含蓄与成人");
        }
        if (body.budgetUsd() != null && body.budgetUsd().signum() < 0) {
            throw new IllegalArgumentException("费用上限不能是负数");
        }
        settings.update(body.scale(), body.budgetUsd());
        return settings.get();
    }

    private JsonNode tree(String text) {
        return text == null ? null : json.readTree(text);
    }

    private static String label(String purpose) {
        try {
            return Purpose.valueOf(purpose).label();
        } catch (IllegalArgumentException e) {
            return purpose;
        }
    }
}
