package app.lyng.mind;

import app.lyng.mind.Markup.Consolidation;
import app.lyng.mind.Prompts.Care;
import app.lyng.mind.Prompts.ViewOf;
import app.lyng.model.CallFailure;
import app.lyng.model.Gateway;
import app.lyng.model.Gateway.Links;
import app.lyng.model.Purpose;
import app.lyng.store.Chats;
import app.lyng.store.Consolidations;
import app.lyng.store.Consolidations.Item;
import app.lyng.store.Inner;
import app.lyng.store.Scenes;
import app.lyng.store.Worlds;
import app.lyng.store.Worlds.Character;
import app.lyng.store.Worlds.World;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 整理内心：一段经历过去后，人物自己写下值得记住的事、对人的看法、牵挂与自我。
 * 在后台进行，同一人物串行；人物开口前先补齐没整理完的。失败不丢经历，下次再做。
 */
@Service
public class Consolidator {

    private static final Logger log = LoggerFactory.getLogger(Consolidator.class);

    static final int CARES = 12;

    private final Gateway gateway;
    private final Consolidations queue;
    private final Inner inner;
    private final Worlds worlds;
    private final Scenes scenes;
    private final Chats chats;
    private final Recall recall;
    private final TransactionTemplate tx;
    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();

    public Consolidator(Gateway gateway, Consolidations queue, Inner inner, Worlds worlds, Scenes scenes, Chats chats,
                        Recall recall, TransactionTemplate tx) {
        this.gateway = gateway;
        this.queue = queue;
        this.inner = inner;
        this.worlds = worlds;
        this.scenes = scenes;
        this.chats = chats;
        this.recall = recall;
        this.tx = tx;
    }

    /** 排一次整理并在后台开始。 */
    public void enqueue(UUID characterId, String sourceKind, UUID sourceId, long fromRef, long toRef) {
        queue.add(characterId, sourceKind, sourceId, fromRef, toRef);
        later(characterId);
    }

    public void later(UUID characterId) {
        inFlight.incrementAndGet();
        Thread.startVirtualThread(() -> {
            try {
                catchUp(characterId);
            } catch (RuntimeException e) {
                log.warn("整理内心失败（人物 {}）：{}", characterId, e.getMessage());
            } finally {
                inFlight.decrementAndGet();
            }
        });
    }

    /** 后台没有正在进行的整理。 */
    public boolean idle() {
        return inFlight.get() == 0;
    }

    /** 服务重启后，把没整理完的接着做。 */
    @EventListener(ApplicationReadyEvent.class)
    public void resume() {
        queue.charactersWithUnfinished().forEach(this::later);
    }

    /** 把这个人物没整理完的都做完（同一人物串行）。单次失败不抛出，留到下次。 */
    public void catchUp(UUID characterId) {
        ReentrantLock lock = locks.computeIfAbsent(characterId, id -> new ReentrantLock());
        lock.lock();
        try {
            for (Item item : queue.unfinished(characterId)) {
                consolidate(item);
            }
            recall.index(characterId);
        } finally {
            lock.unlock();
        }
    }

    private void consolidate(Item item) {
        Character me = worlds.requireCharacter(item.characterId());
        World world = worlds.requireWorld(me.worldId());
        Map<UUID, String> names = new LinkedHashMap<>();
        worlds.characters(me.worldId()).forEach(c -> names.put(c.id(), c.name()));
        String perception;
        String label;
        List<UUID> people = new ArrayList<>();
        Links links;
        if (item.sourceKind().equals("scene")) {
            Scenes.Scene scene = scenes.require(item.sourceId());
            perception = Perceive.scene(scenes.lines(scene.id()), names, me.id());
            label = "第" + scene.number() + "场";
            scenes.cast(scene.id()).stream().filter(id -> !id.equals(me.id())).forEach(people::add);
            links = new Links(me.id(), scene.id(), null, null);
        } else {
            Chats.Conversation c = chats.require(item.sourceId());
            List<Chats.Message> messages = chats.messagesBetween(c.id(), item.fromRef(), item.toRef());
            perception = Perceive.chat(messages, names, me.id());
            UUID other = c.other(me.id());
            label = "和" + names.get(other) + "的私聊";
            people.add(other);
            links = new Links(me.id(), null, c.id(), null);
        }
        List<Inner.Concern> active = inner.activeConcerns(me.id(), CARES);
        List<Care> cares = active.stream()
                .map(c -> new Care(c.text(), c.aboutId() == null ? null : names.get(c.aboutId()))).toList();
        List<ViewOf> views = new ArrayList<>();
        for (UUID p : people) {
            inner.view(me.id(), p).ifPresent(v -> views.add(new ViewOf(names.get(p), v.text())));
        }
        List<String> peopleNames = people.stream().map(names::get).toList();
        String self = inner.self(me.id()).map(Inner.Self::text).orElse(null);
        var messages = Prompts.consolidate(new Prompts.Consolidate(me.name(), world.publicText(), me.profile(), self,
                views, cares, label, perception, peopleNames));
        Map<String, Object> meta = Map.of("name", me.name(), "people", peopleNames, "concerns", active.size());
        Gateway.Result<Consolidation> result;
        try {
            result = gateway.run(Gateway.Spec.of(Purpose.CONSOLIDATE, me.name() + "整理内心（" + label + "）", links,
                    messages, meta), text -> Markup.parseConsolidation(text, peopleNames, active.size()));
        } catch (CallFailure e) {
            queue.failed(item.id(), e.getMessage(), e.runId());
            log.warn("整理内心失败（{}，{}）：{}", me.name(), label, e.getMessage());
            return;
        }
        Consolidation c = result.value();
        UUID runId = result.runId();
        Map<String, UUID> ids = new LinkedHashMap<>();
        names.forEach((id, name) -> ids.put(name, id));
        Map<String, Object> saved = new LinkedHashMap<>();
        tx.executeWithoutResult(status -> {
            List<String> memories = new ArrayList<>();
            for (Markup.NewMemory m : c.memories()) {
                inner.addMemory(me.id(), m.event(), m.meaning(), people, item.sourceKind(), item.sourceId(), label, runId);
                memories.add(m.event());
            }
            c.views().forEach((who, text) -> inner.addView(me.id(), ids.get(who), text, runId));
            List<String> added = new ArrayList<>();
            for (Markup.NewConcern n : c.concerns()) {
                inner.addConcern(me.id(), n.about() == null ? null : ids.get(n.about()), n.text(), runId);
                added.add(n.text());
            }
            List<String> resolved = new ArrayList<>();
            c.resolved().forEach((n, how) -> {
                inner.resolveConcern(active.get(n - 1).id(), how, runId);
                resolved.add(active.get(n - 1).text());
            });
            List<String> background = new ArrayList<>();
            c.background().forEach(n -> {
                inner.backgroundConcern(active.get(n - 1).id(), runId);
                background.add(active.get(n - 1).text());
            });
            List<String> revised = new ArrayList<>();
            c.revised().forEach((n, text) -> {
                inner.reviseConcern(active.get(n - 1).id(), text, runId);
                revised.add(text);
            });
            if (c.self() != null) {
                inner.addSelf(me.id(), c.self(), runId);
            }
            queue.done(item.id(), runId);
            saved.put("记忆", memories);
            saved.put("看法", c.views());
            saved.put("新的牵挂", added);
            saved.put("了结的牵挂", resolved);
            saved.put("放下的牵挂", background);
            saved.put("改了的牵挂", revised);
            saved.put("自我", c.self());
        });
        gateway.outcome(runId, saved);
    }
}
