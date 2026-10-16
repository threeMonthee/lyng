package app.lyng.chat;

import app.lyng.mind.Consolidator;
import app.lyng.mind.Events;
import app.lyng.mind.Markup.Beat;
import app.lyng.mind.Markup.Turn;
import app.lyng.mind.Perceive;
import app.lyng.mind.Prompts;
import app.lyng.mind.Prompts.Opportunity;
import app.lyng.mind.Voice;
import app.lyng.mind.Witness;
import app.lyng.model.CallFailure;
import app.lyng.model.Gateway;
import app.lyng.model.Gateway.Links;
import app.lyng.store.Chats;
import app.lyng.store.Chats.Conversation;
import app.lyng.store.Chats.Message;
import app.lyng.store.Conflict;
import app.lyng.store.Consolidations;
import app.lyng.store.Inner;
import app.lyng.store.Scenes;
import app.lyng.store.Settings;
import app.lyng.store.Worlds;
import app.lyng.store.Worlds.Character;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 私聊：两个人物之间不在眼前的文字往来。你附身其中一方，另一方由 AI 驱动。
 * 你发一句，对方得到一次回应的机会；“继续”再给一次机会；你回来时，心里有关于你的牵挂的人物可能主动开口。
 *
 * <p>待做的一步记成“人物id|机会”（reply / continue / reach），失败就停在这一步，重试接着做。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    static final int TRANSCRIPT = 40;
    static final int CONSOLIDATE_EVERY = 24;
    static final Duration QUIET = Duration.ofMinutes(30);
    static final int REACH_LIMIT = 3;

    private final Chats chats;
    private final Worlds worlds;
    private final Scenes scenes;
    private final Inner inner;
    private final Settings settings;
    private final Gateway gateway;
    private final Voice voice;
    private final Witness witness;
    private final Consolidator consolidator;
    private final Consolidations consolidations;
    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final AtomicInteger reaching = new AtomicInteger();

    public ChatService(Chats chats, Worlds worlds, Scenes scenes, Inner inner, Settings settings, Gateway gateway,
                       Voice voice, Witness witness, Consolidator consolidator, Consolidations consolidations) {
        this.chats = chats;
        this.worlds = worlds;
        this.scenes = scenes;
        this.inner = inner;
        this.settings = settings;
        this.gateway = gateway;
        this.voice = voice;
        this.witness = witness;
        this.consolidator = consolidator;
        this.consolidations = consolidations;
    }

    /** 两个人物之间的私聊（没有就建），并记下你附身哪一方。 */
    public Conversation open(UUID playedId, UUID otherId) {
        if (playedId.equals(otherId)) {
            throw new IllegalArgumentException("选两个不同的人物");
        }
        Character played = worlds.requireCharacter(playedId);
        Character other = worlds.requireCharacter(otherId);
        if (!played.worldId().equals(other.worldId())) {
            throw new IllegalArgumentException("私聊的两个人物要在同一个世界");
        }
        Conversation c = chats.open(playedId, otherId);
        chats.setPlayed(c.id(), playedId);
        return chats.require(c.id());
    }

    /** 换成附身另一方。 */
    public void play(UUID conversationId, UUID playedId) {
        withLock(conversationId, () -> {
            Conversation c = chats.require(conversationId);
            if (!c.has(playedId)) {
                throw new IllegalArgumentException("只能附身这段私聊里的一方");
            }
            if (!c.pending().isEmpty()) {
                throw new Conflict("对方还没回完，稍等或先重试");
            }
            chats.setPlayed(conversationId, playedId);
        });
    }

    public void send(UUID conversationId, String text, Events events) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("写下要说的话");
        }
        withLock(conversationId, () -> {
            Conversation c = ready(conversationId);
            Map<UUID, String> names = voice.names(worlds.requireCharacter(c.aId()).worldId());
            List<Long> ids = new ArrayList<>();
            for (String line : text.strip().split("\\R+")) {
                if (!line.isBlank()) {
                    ids.add(witness.message(c, names, c.playedId(), "say", line.strip(), true, "user", false, null));
                }
            }
            events.emit("saved", Map.of("messages", messages(conversationId, ids)));
            chats.setPending(conversationId, List.of(step(c.other(c.playedId()), Opportunity.REPLY)), null);
            runPending(conversationId, events, false);
        });
    }

    public void proceed(UUID conversationId, Events events) {
        withLock(conversationId, () -> {
            Conversation c = ready(conversationId);
            chats.setPending(conversationId, List.of(step(c.other(c.playedId()), Opportunity.CONTINUE)), null);
            runPending(conversationId, events, false);
        });
    }

    public void retry(UUID conversationId, Events events) {
        withLock(conversationId, () -> runPending(conversationId, events, false));
    }

    public void read(UUID conversationId) {
        chats.require(conversationId);
        chats.markRead(conversationId);
    }

    /** 你离开这段私聊：双方把新的话整理进内心。 */
    public void leave(UUID conversationId) {
        Conversation c = chats.require(conversationId);
        consolidate(c, 1);
    }

    public boolean running(UUID conversationId) {
        ReentrantLock lock = locks.get(conversationId);
        return lock != null && lock.isLocked();
    }

    /**
     * 你回来了。距上次超过半小时才算：每段你附身过的私聊里，对方若有关于你所附身人物的牵挂、
     * 最后一句话在半小时前、TA 不在进行中的戏里，就得到一次主动开口的机会（每次至多三段，后台串行）。
     */
    public int presence() {
        Instant last = settings.get().lastPresence();
        settings.touchPresence();
        if (last != null && Duration.between(last, Instant.now()).compareTo(QUIET) < 0) {
            return 0;
        }
        List<UUID> chosen = new ArrayList<>();
        for (Conversation c : chats.active()) {
            if (chosen.size() >= REACH_LIMIT) {
                break;
            }
            if (c.playedId() == null || !c.pending().isEmpty() || running(c.id())) {
                continue;
            }
            UUID other = c.other(c.playedId());
            if (inner.activeConcernsAbout(other, c.playedId()).isEmpty() || !scenes.openWith(other).isEmpty()) {
                continue;
            }
            boolean quiet = chats.last(c.id()).map(m -> Duration.between(m.createdAt(), Instant.now()).compareTo(QUIET) >= 0)
                    .orElse(false);
            if (quiet) {
                chosen.add(c.id());
            }
        }
        if (!chosen.isEmpty()) {
            reaching.incrementAndGet();
            Thread.startVirtualThread(() -> {
                try {
                    reach(chosen);
                } finally {
                    reaching.decrementAndGet();
                }
            });
        }
        return chosen.size();
    }

    /** 后台没有正在进行的主动开口。 */
    public boolean idle() {
        return reaching.get() == 0;
    }

    private void reach(List<UUID> chosen) {
        for (UUID id : chosen) {
            try {
                withLock(id, () -> {
                    Conversation c = chats.require(id);
                    chats.setPending(id, List.of(step(c.other(c.playedId()), Opportunity.REACH)), null);
                    runPending(id, Events.NONE, true);
                });
            } catch (RuntimeException e) {
                log.warn("主动开口没有完成（私聊 {}）：{}", id, e.getMessage());
            }
        }
    }

    // ---- 步骤 ----

    private void runPending(UUID conversationId, Events events, boolean unread) {
        Conversation c = chats.require(conversationId);
        if (c.pending().isEmpty()) {
            events.emit("done", Map.of());
            return;
        }
        String[] step = c.pending().getFirst().split("\\|");
        UUID speakerId = UUID.fromString(step[0]);
        Opportunity opportunity = Opportunity.valueOf(step[1]);
        try {
            speak(c, speakerId, opportunity, events, unread || opportunity == Opportunity.REACH);
        } catch (CallFailure e) {
            chats.setPending(conversationId, c.pending(), e.getMessage());
            Map<String, Object> data = new HashMap<>();
            data.put("message", e.getMessage());
            data.put("runId", e.runId());
            events.emit("failed", data);
            return;
        }
        chats.setPending(conversationId, List.of(), null);
        consolidate(chats.require(conversationId), CONSOLIDATE_EVERY);
        events.emit("done", Map.of());
    }

    private void speak(Conversation c, UUID speakerId, Opportunity opportunity, Events events, boolean unread) {
        Character me = worlds.requireCharacter(speakerId);
        UUID otherId = c.other(speakerId);
        Character other = worlds.requireCharacter(otherId);
        var world = worlds.requireWorld(me.worldId());
        Map<UUID, String> names = voice.names(me.worldId());
        events.emit("status", Map.of("step", "character", "speakerId", speakerId));
        List<Message> recent = chats.messages(c.id(), Long.MAX_VALUE, TRANSCRIPT);
        long fromRef = recent.isEmpty() ? Long.MAX_VALUE : recent.getFirst().id();
        String situation = recent.stream().skip(Math.max(0, recent.size() - 6))
                .map(m -> Perceive.message(m, names, speakerId)).filter(s -> s != null)
                .reduce((a, b) -> a + "\n" + b).orElse(other.name());
        Voice.Here here = new Voice.Here("chat", c.id(), fromRef);
        var persona = voice.persona(me, world, List.of(other), situation, here, settings.get().scale());
        var messages = Prompts.characterInChat(persona, other.name(), Perceive.chat(recent, names, speakerId),
                opportunity, Perceive.when(Instant.now()));
        String title = me.name() + " · 和" + other.name() + "的私聊（" + switch (opportunity) {
            case REPLY -> "回应";
            case CONTINUE -> "继续";
            case REACH -> "主动";
        } + "）";
        var result = voice.speak(me, messages, false, new Links(speakerId, null, c.id(), null), title, here,
                new Voice.Sink() {
                    @Override
                    public void beat(Beat beat) {
                        Map<String, Object> data = new LinkedHashMap<>();
                        data.put("speakerId", speakerId);
                        data.put("kind", "say");
                        data.put("text", beat.text());
                        events.emit("draft", data);
                    }

                    @Override
                    public void discard() {
                        events.emit("discard", Map.of());
                    }
                });
        Turn turn = result.value();
        String kind = opportunity.name().toLowerCase();
        List<Long> ids = new ArrayList<>();
        for (Beat b : turn.beats()) {
            for (String line : b.text().split("\\R+")) {
                if (!line.isBlank()) {
                    ids.add(witness.message(c, names, speakerId, "say", line.strip(), false, kind, unread, result.runId()));
                }
            }
        }
        ids.add(witness.message(c, names, speakerId, "inner", turn.inner(), false, kind, false, result.runId()));
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("发出的话", turn.beats().stream().map(Beat::text).toList());
        outcome.put("心声", turn.inner());
        gateway.outcome(result.runId(), outcome);
        events.emit("saved", Map.of("messages", messages(c.id(), ids)));
    }

    /** 双方各自把还没排进整理的话（至少 minimum 句说出口的话）排进整理。 */
    private void consolidate(Conversation c, int minimum) {
        long last = chats.lastId(c.id());
        for (UUID who : List.of(c.aId(), c.bId())) {
            long covered = consolidations.coveredUpTo(who, c.id());
            if (last <= covered) {
                continue;
            }
            long said = chats.messagesBetween(c.id(), covered + 1, last).stream().filter(m -> m.kind().equals("say")).count();
            if (said >= minimum) {
                consolidator.enqueue(who, "chat", c.id(), covered + 1, last);
            }
        }
    }

    private Conversation ready(UUID conversationId) {
        Conversation c = chats.require(conversationId);
        if (c.playedId() == null) {
            throw new Conflict("先选你附身哪一方");
        }
        if (!c.pending().isEmpty()) {
            throw new Conflict(c.failure() != null ? "上一次没有成功，先重试" : "对方还没回完，稍等");
        }
        for (UUID who : List.of(c.aId(), c.bId())) {
            var busy = scenes.openWith(who);
            if (!busy.isEmpty()) {
                throw new Conflict(worlds.requireCharacter(who).name() + "正在第" + busy.getFirst().number()
                        + "场里，先结束那一场");
            }
        }
        return c;
    }

    private List<Message> messages(UUID conversationId, List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return chats.messagesBetween(conversationId, ids.getFirst(), ids.getLast());
    }

    private static String step(UUID speakerId, Opportunity opportunity) {
        return speakerId + "|" + opportunity.name();
    }

    private void withLock(UUID conversationId, Runnable body) {
        ReentrantLock lock = locks.computeIfAbsent(conversationId, id -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new Conflict("对方还在回，稍等");
        }
        try {
            body.run();
        } finally {
            lock.unlock();
        }
    }
}
