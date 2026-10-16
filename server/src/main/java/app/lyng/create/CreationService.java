package app.lyng.create;

import app.lyng.mind.Events;
import app.lyng.mind.Markup;
import app.lyng.mind.Markup.Draft;
import app.lyng.mind.Markup.DraftCharacter;
import app.lyng.mind.Markup.DraftConcern;
import app.lyng.mind.Markup.DraftView;
import app.lyng.mind.Markup.Question;
import app.lyng.mind.Prompts;
import app.lyng.mind.Prompts.Answer;
import app.lyng.model.CallFailure;
import app.lyng.model.FormatError;
import app.lyng.model.Gateway;
import app.lyng.model.Gateway.Links;
import app.lyng.model.Purpose;
import app.lyng.store.Conflict;
import app.lyng.store.Drafts;
import app.lyng.store.Inner;
import app.lyng.store.Worlds;
import app.lyng.store.Worlds.Character;
import app.lyng.store.Worlds.World;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * 一念生：一句话 →（必要时追问）→ 写稿 → 一致性检查 → 手改或让 AI 改一段 → 诞生。
 * 草稿诞生前可以随意改，诞生后世界与人物的出生档案不再改。
 */
@Service
public class CreationService {

    /** 页面看到的一份草稿。 */
    public record Page(UUID id, UUID worldId, String worldName, String request, String status, List<Question> questions,
                       List<Answer> answers, Draft content, List<Markup.Conflict> conflicts, String failure,
                       Instant updatedAt) {}

    /** AI 改好的一段：采用后由页面写回草稿。 */
    public record Revision(String target, String text, UUID runId) {}

    public record Born(UUID worldId, List<UUID> characterIds) {}

    private static final Pattern CHARACTER_TARGET = Pattern.compile("^character\\.(\\d+)$");

    private final Drafts drafts;
    private final Worlds worlds;
    private final Inner inner;
    private final Gateway gateway;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    public CreationService(Drafts drafts, Worlds worlds, Inner inner, Gateway gateway, TransactionTemplate tx,
                           JsonMapper json) {
        this.drafts = drafts;
        this.worlds = worlds;
        this.inner = inner;
        this.gateway = gateway;
        this.tx = tx;
        this.json = json;
    }

    /** 服务重启时还在“写”的草稿已经中断了，标成失败，可以重写。 */
    @EventListener(ApplicationReadyEvent.class)
    public void interrupted() {
        drafts.interrupted("服务重启，写稿中断了");
    }

    public List<Page> unfinished() {
        return drafts.unfinished().stream().map(this::page).toList();
    }

    public Page page(UUID draftId) {
        return page(drafts.require(draftId));
    }

    /**
     * 新的一念：记下这句话，再看要不要先问几件事。worldId 不为空表示在已有世界里添人物。
     * 追问失败时草稿照样返回（带着失败），页面可以再问一次或跳过追问直接写。
     */
    public Page start(UUID worldId, String request) {
        if (request == null || request.isBlank()) {
            throw new IllegalArgumentException("用一句话写下你想进入的世界、想遇见的人");
        }
        if (worldId != null) {
            worlds.requireWorld(worldId);
        }
        UUID id = drafts.create(worldId, request.strip());
        try {
            return ask(id);
        } catch (CallFailure e) {
            return page(id);
        }
    }

    /** 追问（失败后可以再来一次）。 */
    public Page ask(UUID draftId) {
        return withLock(draftId, () -> {
            Drafts.Draft d = drafts.require(draftId);
            if (!d.status().equals("asking")) {
                throw new Conflict("这份草稿已经过了追问这一步");
            }
            World world = d.worldId() == null ? null : worlds.requireWorld(d.worldId());
            try {
                var result = gateway.run(Gateway.Spec.of(Purpose.ASK, "一念生 · 追问", links(draftId),
                        Prompts.ask(d.request(), world == null ? null : world.publicText(), names(world)), Map.of()),
                        Markup::parseQuestions);
                drafts.update(draftId, "asking", write(result.value()), null, null, null, null);
                gateway.outcome(result.runId(), Map.of("追问", result.value()));
            } catch (CallFailure e) {
                drafts.update(draftId, "asking", null, null, null, null, e.getMessage());
                throw e;
            }
            return page(draftId);
        });
    }

    /** 带着对追问的回答写稿，写完自动做一次一致性检查。answers 可以为空（跳过追问）。 */
    public void write(UUID draftId, List<Answer> answers, Events events) {
        withLock(draftId, () -> {
            Drafts.Draft d = drafts.require(draftId);
            if (d.status().equals("born")) {
                throw new Conflict("这份草稿已经诞生了");
            }
            List<Answer> given = answers == null ? List.of() : answers.stream()
                    .filter(a -> a.answer() != null && !a.answer().isBlank()).toList();
            World world = d.worldId() == null ? null : worlds.requireWorld(d.worldId());
            List<String> known = names(world);
            drafts.update(draftId, "writing", d.questions(), write(given), d.content(), null, null);
            events.emit("status", Map.of("step", "writing"));
            Draft draft;
            try {
                var result = gateway.run(new Gateway.Spec(Purpose.WRITE, "一念生 · 写稿", links(draftId),
                        Prompts.write(d.request(), given, world == null ? null : world.publicText(), known), List.of(),
                        null, Map.of()), () -> new Gateway.Attempt<Draft>() {
                            private final StringBuilder line = new StringBuilder();

                            @Override
                            public void content(String delta) {
                                for (char c : delta.toCharArray()) {
                                    if (c == '\n') {
                                        announce(line.toString(), events);
                                        line.setLength(0);
                                    } else {
                                        line.append(c);
                                    }
                                }
                            }

                            @Override
                            public void abandon() {
                                line.setLength(0);
                                events.emit("discard", Map.of());
                            }

                            @Override
                            public Draft finish(String text) {
                                return Markup.parseDraft(text, world == null, known);
                            }
                        });
                draft = result.value();
                gateway.outcome(result.runId(), Map.of("人物", draft.characters().stream().map(DraftCharacter::name).toList()));
            } catch (CallFailure e) {
                drafts.update(draftId, "failed", d.questions(), write(given), d.content(), null, e.getMessage());
                Map<String, Object> data = new HashMap<>();
                data.put("message", e.getMessage());
                data.put("runId", e.runId());
                events.emit("failed", data);
                return;
            }
            drafts.update(draftId, "ready", d.questions(), write(given), write(draft), null, null);
            events.emit("saved", Map.of("draft", page(draftId)));
            checkInside(draftId, events);
            events.emit("done", Map.of());
        });
    }

    /** 手改：页面交回完整的草稿，按写稿的规矩校验。 */
    public Page edit(UUID draftId, Draft content) {
        return withLock(draftId, () -> {
            Drafts.Draft d = editable(draftId);
            World world = d.worldId() == null ? null : worlds.requireWorld(d.worldId());
            Draft checked;
            try {
                checked = Markup.parseDraft(render(content), world == null, names(world));
            } catch (FormatError e) {
                throw new IllegalArgumentException(e.getMessage());
            }
            drafts.update(draftId, "ready", d.questions(), d.answers(), write(checked), d.checkResult(), null);
            return page(draftId);
        });
    }

    /**
     * 让 AI 只改一段。target 是 world.public、world.narrator 或 character.N（第 N 个人物的档案，从 0 起）。
     * 返回改好的文字，不写回草稿。
     */
    public Revision revise(UUID draftId, String target, String instruction) {
        if (instruction == null || instruction.isBlank()) {
            throw new IllegalArgumentException("说说想怎么改");
        }
        return withLock(draftId, () -> {
            Drafts.Draft d = editable(draftId);
            Draft draft = read(d.content(), Draft.class);
            String label;
            String current;
            Matcher m = CHARACTER_TARGET.matcher(target == null ? "" : target);
            if ("world.public".equals(target) && draft.world() != null) {
                label = "世界·公开";
                current = draft.world().publicText();
            } else if ("world.narrator".equals(target) && draft.world() != null) {
                label = "世界·只有叙事者知道";
                current = draft.world().narratorText();
            } else if (m.matches() && Integer.parseInt(m.group(1)) < draft.characters().size()) {
                DraftCharacter c = draft.characters().get(Integer.parseInt(m.group(1)));
                label = c.name() + "的档案";
                current = c.profile();
            } else {
                throw new IllegalArgumentException("没有这一段");
            }
            var result = gateway.run(Gateway.Spec.of(Purpose.REVISE, "一念生 · 改" + label, links(draftId),
                    Prompts.revise(render(draft), label, current, instruction.strip()), Map.of()), Markup::parseRevision);
            return new Revision(target, result.value(), result.runId());
        });
    }

    /** 一致性检查。 */
    public Page check(UUID draftId) {
        return withLock(draftId, () -> {
            editable(draftId);
            checkOrThrow(draftId);
            return page(draftId);
        });
    }

    /** 诞生：一个事务写下世界、人物、初始看法与牵挂，草稿封存。 */
    public Born birth(UUID draftId) {
        return withLock(draftId, () -> {
            Drafts.Draft d = editable(draftId);
            Draft draft = read(d.content(), Draft.class);
            return tx.execute(status -> {
                Born born = bear(d.worldId(), draft);
                drafts.update(draftId, "born", d.questions(), d.answers(), d.content(), d.checkResult(), null);
                return born;
            });
        });
    }

    /** 让一份解析好的草稿诞生（种子世界也走这里）。worldId 为空表示连世界一起诞生。 */
    public Born bear(UUID worldId, Draft draft) {
        return tx.execute(status -> {
            UUID world = worldId != null ? worldId
                    : worlds.createWorld(draft.world().name(), draft.world().publicText(), draft.world().narratorText());
            Map<String, UUID> ids = new LinkedHashMap<>();
            worlds.characters(world).forEach(c -> ids.put(c.name(), c.id()));
            List<UUID> born = new ArrayList<>();
            for (DraftCharacter c : draft.characters()) {
                if (ids.containsKey(c.name())) {
                    throw new Conflict("这个世界里已经有叫“" + c.name() + "”的人物，换个名字");
                }
                UUID id = worlds.createCharacter(world, c.name(), c.profile());
                ids.put(c.name(), id);
                born.add(id);
            }
            for (int i = 0; i < draft.characters().size(); i++) {
                DraftCharacter c = draft.characters().get(i);
                UUID me = born.get(i);
                for (DraftView v : c.views()) {
                    inner.addView(me, ids.get(v.name()), v.text(), null);
                }
                for (DraftConcern concern : c.concerns()) {
                    inner.addConcern(me, concern.about() == null ? null : ids.get(concern.about()), concern.text(), null);
                }
            }
            return new Born(world, born);
        });
    }

    public void delete(UUID draftId) {
        Drafts.Draft d = drafts.require(draftId);
        if (d.status().equals("born")) {
            throw new Conflict("已经诞生的草稿不能删除");
        }
        drafts.delete(draftId);
    }

    // ---- 小工具 ----

    private void checkInside(UUID draftId, Events events) {
        events.emit("status", Map.of("step", "checking"));
        try {
            checkOrThrow(draftId);
            events.emit("saved", Map.of("draft", page(draftId)));
        } catch (CallFailure e) {
            events.emit("notice", Map.of("message", "一致性检查没有完成（" + e.getMessage() + "），可以稍后再查"));
        }
    }

    private void checkOrThrow(UUID draftId) {
        Drafts.Draft d = drafts.require(draftId);
        World world = d.worldId() == null ? null : worlds.requireWorld(d.worldId());
        Draft draft = read(d.content(), Draft.class);
        var result = gateway.run(Gateway.Spec.of(Purpose.CHECK, "一念生 · 一致性检查", links(draftId),
                Prompts.check(render(draft), world == null ? null : world.publicText()), Map.of()), Markup::parseConflicts);
        Drafts.Draft now = drafts.require(draftId);
        drafts.update(draftId, now.status(), now.questions(), now.answers(), now.content(), write(result.value()), null);
        gateway.outcome(result.runId(), Map.of("冲突", result.value()));
    }

    /** 草稿写回标记文本：给模型看，也用来按写稿的规矩校验手改。 */
    static String render(Draft d) {
        StringBuilder out = new StringBuilder();
        if (d.world() != null) {
            out.append("【世界名】").append(nullToEmpty(d.world().name())).append('\n')
                    .append("【世界·公开】\n").append(nullToEmpty(d.world().publicText())).append('\n')
                    .append("【世界·只有叙事者知道】\n").append(nullToEmpty(d.world().narratorText())).append('\n');
        }
        for (DraftCharacter c : d.characters() == null ? List.<DraftCharacter>of() : d.characters()) {
            out.append("【人物】").append(nullToEmpty(c.name()).strip()).append('\n')
                    .append(nullToEmpty(c.profile()).strip()).append('\n');
            for (DraftView v : c.views() == null ? List.<DraftView>of() : c.views()) {
                out.append("【看法·").append(v.name()).append("】").append(nullToEmpty(v.text())).append('\n');
            }
            for (DraftConcern concern : c.concerns() == null ? List.<DraftConcern>of() : c.concerns()) {
                out.append(concern.about() == null || concern.about().isBlank() ? "【牵挂】" : "【牵挂·" + concern.about() + "】")
                        .append(nullToEmpty(concern.text())).append('\n');
            }
        }
        return out.toString().strip();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 写稿时每写到一个新段落，告诉页面写到哪了。 */
    private static void announce(String line, Events events) {
        String s = line.strip();
        if (s.startsWith("【人物】")) {
            events.emit("status", Map.of("step", "writing", "name", s.substring(4).strip()));
        } else if (s.startsWith("【世界名】")) {
            events.emit("status", Map.of("step", "writing", "name", "世界"));
        }
    }

    private Drafts.Draft editable(UUID draftId) {
        Drafts.Draft d = drafts.require(draftId);
        if (d.status().equals("born")) {
            throw new Conflict("这份草稿已经诞生了");
        }
        if (d.content() == null) {
            throw new Conflict("还没有写好的稿子");
        }
        return d;
    }

    private Page page(Drafts.Draft d) {
        String worldName = d.worldId() == null ? null : worlds.world(d.worldId()).map(World::name).orElse(null);
        return new Page(d.id(), d.worldId(), worldName, d.request(), d.status(),
                d.questions() == null ? null : read(d.questions(), new TypeReference<List<Question>>() {}),
                d.answers() == null ? List.of() : read(d.answers(), new TypeReference<List<Answer>>() {}),
                d.content() == null ? null : read(d.content(), Draft.class),
                d.checkResult() == null ? null : read(d.checkResult(), new TypeReference<List<Markup.Conflict>>() {}),
                d.failure(), d.updatedAt());
    }

    private List<String> names(World world) {
        return world == null ? List.of() : worlds.characters(world.id()).stream().map(Character::name).toList();
    }

    private static Links links(UUID draftId) {
        return new Links(null, null, null, draftId);
    }

    private String write(Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }

    private <T> T read(String text, Class<T> type) {
        return json.readValue(text, type);
    }

    private <T> T read(String text, TypeReference<T> type) {
        return json.readValue(text, type);
    }

    private <T> T withLock(UUID draftId, java.util.function.Supplier<T> body) {
        ReentrantLock lock = locks.computeIfAbsent(draftId, id -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new Conflict("这份草稿正在处理，稍等");
        }
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }

    private void withLock(UUID draftId, Runnable body) {
        withLock(draftId, () -> {
            body.run();
            return null;
        });
    }
}
