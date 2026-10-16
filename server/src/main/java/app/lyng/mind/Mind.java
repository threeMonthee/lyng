package app.lyng.mind;

import app.lyng.mind.Decision.ConcernChange;
import app.lyng.mind.Decision.Link;
import app.lyng.mind.Decision.MemoryChange;
import app.lyng.mind.Decision.ViewChange;
import app.lyng.mind.MindPrompts.Line;
import app.lyng.mind.MindPrompts.Person;
import app.lyng.mind.Prompts.Opportunity;
import app.lyng.model.CallFailure;
import app.lyng.model.FormatError;
import app.lyng.model.Gateway;
import app.lyng.model.Gateway.Links;
import app.lyng.model.Purpose;
import app.lyng.store.Chats;
import app.lyng.store.Chats.Conversation;
import app.lyng.store.Consolidations;
import app.lyng.store.Inner;
import app.lyng.store.Scenes;
import app.lyng.store.Settings;
import app.lyng.store.Traces;
import app.lyng.store.Worlds;
import app.lyng.store.Worlds.Character;
import app.lyng.store.Worlds.World;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 私聊里一个 AI 人物开口：先在心里过一遍（照搬 x-chat-ai 的人物心智：读自己的处境与过去，
 * 私下决定说不说、说什么，承接这一轮之后仍有影响的变化），再把选定的话说出口。
 * 两步都成功，说出的话、心声与内心的变化才一起保存；任何一步失败都不留下半个回应，重试从头再来。
 */
@Service
public class Mind {

    /** 交流里最多带几条说出口的话（其间自己的心声一并带上）。 */
    static final int EXCHANGE = 40;
    static final int EXCHANGE_ROWS = 120;
    static final int ACTIVE_SHOWN = 12;
    static final int MAX_ACTIVE = 8;
    static final int KNOWN_SHOWN = 12;
    static final int CUES = 6;
    /** 说出口时看得到的最近几条消息。 */
    static final int RECENT_FOR_EXPRESSION = 12;

    /** 流式接收说出口的话：一行写完就交出；discard 表示这次尝试作废，已交出的要收回。 */
    public interface Sink {
        void line(String text);

        void discard();
    }

    private final Gateway gateway;
    private final Inner inner;
    private final Traces traces;
    private final Chats chats;
    private final Scenes scenes;
    private final Worlds worlds;
    private final Settings settings;
    private final Witness witness;
    private final Consolidator consolidator;
    private final Consolidations consolidations;
    private final Recollection recollection;
    private final MindTools tools;
    private final TransactionTemplate tx;

    public Mind(Gateway gateway, Inner inner, Traces traces, Chats chats, Scenes scenes, Worlds worlds,
                Settings settings, Witness witness, Consolidator consolidator, Consolidations consolidations,
                Recollection recollection, MindTools tools, TransactionTemplate tx) {
        this.gateway = gateway;
        this.inner = inner;
        this.traces = traces;
        this.chats = chats;
        this.scenes = scenes;
        this.worlds = worlds;
        this.settings = settings;
        this.witness = witness;
        this.consolidator = consolidator;
        this.consolidations = consolidations;
        this.recollection = recollection;
        this.tools = tools;
        this.tx = tx;
    }

    /** 开口。返回保存下来的几行（说出的话，最后是心声）；失败抛 CallFailure，什么也不保存。 */
    public List<Long> speak(Conversation c, UUID speakerId, Opportunity opportunity, boolean unread, Sink sink) {
        Character me = worlds.requireCharacter(speakerId);
        Character other = worlds.requireCharacter(c.other(speakerId));
        World world = worlds.requireWorld(me.worldId());
        Map<UUID, String> names = new LinkedHashMap<>();
        worlds.characters(me.worldId()).forEach(x -> names.put(x.id(), x.name()));
        consolidator.catchUp(me.id());

        List<Line> lines = exchange(me.id(), c.id());
        int split = lines.size();
        if (opportunity == Opportunity.REPLY) {
            while (split > 0 && !lines.get(split - 1).mine()) {
                split--;
            }
        }
        List<Line> current = lines.subList(split, lines.size());
        String moment = switch (opportunity) {
            case REPLY -> current.isEmpty() ? "CONTINUE" : "MESSAGE";
            case CONTINUE -> "CONTINUE";
            case REACH -> "ONLINE";
        };
        Map<UUID, String> views = new LinkedHashMap<>();
        inner.views(me.id()).forEach(v -> views.put(v.otherId(), v.text()));
        List<Person> known = known(me.id(), other.id(), names, views);
        Person counterpart = known.getFirst();
        List<Inner.Concern> active = inner.activeConcerns(me.id(), ACTIVE_SHOWN);
        List<MindPrompts.LifeCue> cues = new ArrayList<>();
        inner.browseConcerns(me.id(), "", 0, CUES).forEach(x -> cues.add(
                new MindPrompts.LifeCue("concern", x.id(), x.aboutId(), x.cueOrText())));
        inner.browseMemories(me.id(), "", 0, CUES).forEach(x -> cues.add(
                new MindPrompts.LifeCue("memory", x.id(), x.aboutId(), x.cueOrText())));
        Instant now = Instant.now();
        List<Recollection.Bundle> material = recollection.enrich(me.id(), lines.stream().map(Line::id).toList(),
                lines.isEmpty() ? null : lines.getLast().text(), active.stream().map(Inner.Concern::text).toList(), now);
        var context = new MindPrompts.Context(me.name(), world.publicText(), me.profile(), settings.get().scale(),
                inner.self(me.id()).map(Inner.Self::text).orElse(null), moment, now, counterpart,
                active.stream().map(x -> new MindPrompts.Concern(x.id(), x.aboutId(), x.cueOrText(), x.text(),
                        x.status())).toList(),
                known.stream().skip(1).limit(KNOWN_SHOWN).toList(), known.size(), cues, material,
                lines.subList(0, split), current);

        String title = me.name() + " · 和" + other.name() + "的私聊（" + switch (opportunity) {
            case REPLY -> "回应";
            case CONTINUE -> "继续";
            case REACH -> "主动";
        } + "）";
        Links links = new Links(me.id(), null, c.id(), null);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", me.name());
        meta.put("personId", other.id().toString());
        meta.put("opportunity", moment);
        meta.put("traces", lines.stream().map(Line::id).toList());
        meta.put("concerns", active.stream().map(x -> x.id().toString()).toList());
        Set<UUID> knownIds = new HashSet<>();
        known.forEach(p -> knownIds.add(p.id()));
        Gateway.Result<Decision> decided = gateway.run(new Gateway.Spec(Purpose.MIND, title + "：心里过一遍", links,
                MindPrompts.mind(context), tools.tools(), tools.runner(me.id(), me.name(), known), meta),
                text -> check(me.id(), Decision.parse(text), knownIds));
        Decision d = decided.value();

        List<String> spoken = List.of();
        UUID spokenRun = null;
        if (!d.silent()) {
            List<String> recent = lines.stream().filter(l -> l.kind().equals("say")).map(l ->
                    (l.mine() ? me.name() : other.name()) + "：" + l.text()).toList();
            recent = recent.subList(Math.max(0, recent.size() - RECENT_FOR_EXPRESSION), recent.size());
            Map<String, Object> said = new LinkedHashMap<>();
            said.put("name", me.name());
            said.put("intent", d.publicIntent());
            try {
                Gateway.Result<List<String>> expressed = gateway.run(new Gateway.Spec(Purpose.EXPRESS,
                        title + "：说出口", links, MindPrompts.express(me.name(), me.profile(), other.name(),
                        counterpart.view(), d.innerVoice(), recent, d.publicIntent()), List.of(), null, said),
                        () -> new Expression(me.name(), sink));
                spoken = expressed.value();
                spokenRun = expressed.runId();
            } catch (CallFailure e) {
                gateway.outcome(decided.runId(), Map.of("没有保存", "说出口的那一步没有成功，这一轮整个作废"));
                throw e;
            }
        }

        List<String> lines1 = spoken;
        UUID expressRun = spokenRun;
        String kind = opportunity.name().toLowerCase();
        UUID run = decided.runId();
        List<Long> ids = tx.execute(status -> {
            List<Long> saved = new ArrayList<>();
            // 一次开口的几行记在心里那一步的运行上（幕后从这里看起；说出口的运行在它之后）
            for (String line : lines1) {
                saved.add(witness.message(c, names, me.id(), "say", line, false, kind, unread, run));
            }
            saved.add(witness.message(c, names, me.id(), "inner", d.innerVoice(), false, kind, false, run));
            apply(me.id(), other, c, d.changes(), run);
            long from = consolidations.coveredUpTo(me.id(), c.id()) + 1;
            consolidations.taken(me.id(), "chat", c.id(), from, saved.getLast(), run);
            return saved;
        });
        Map<String, Object> outcome = new LinkedHashMap<>();
        if (d.silent()) {
            outcome.put("沉默的理由", d.silenceReason());
        } else {
            outcome.put("选定公开的内容", d.publicIntent());
        }
        outcome.put("心声", d.innerVoice());
        outcome.put("变化", changes(d.changes(), names));
        gateway.outcome(run, outcome);
        if (expressRun != null) {
            gateway.outcome(expressRun, Map.of("发出的话", spoken));
        }
        return ids;
    }

    /** 这段私聊里最近的话：最多 EXCHANGE 条说出口的，连同其间自己的心声。 */
    private List<Line> exchange(UUID me, UUID conversationId) {
        List<Traces.ChatLine> rows = traces.chat(me, conversationId, Long.MAX_VALUE, EXCHANGE_ROWS);
        int start = rows.size();
        int says = 0;
        while (start > 0) {
            if (rows.get(start - 1).kind().equals("say")) {
                if (says == EXCHANGE) {
                    break;
                }
                says++;
            }
            start--;
        }
        return rows.subList(start, rows.size()).stream()
                .map(l -> new Line(l.id(), l.speakerId().equals(me), l.kind(), l.text(), l.createdAt())).toList();
    }

    /** 认识的人：眼前的人在最前，然后是有看法的、私聊过的、同场过的。 */
    private List<Person> known(UUID me, UUID other, Map<UUID, String> names, Map<UUID, String> views) {
        Set<UUID> ids = new LinkedHashSet<>();
        ids.add(other);
        ids.addAll(views.keySet());
        chats.with(me).stream().filter(x -> chats.lastId(x.id()) > 0).forEach(x -> ids.add(x.other(me)));
        ids.addAll(scenes.castmates(me));
        ids.remove(me);
        return ids.stream().filter(names::containsKey).map(id -> new Person(id, names.get(id), views.get(id))).toList();
    }

    /** 照 x-chat-ai 的规矩校验交接里提到的人、牵挂与材料；不合规的整份作废。 */
    private Decision check(UUID me, Decision d, Set<UUID> known) {
        Decision.Changes ch = d.changes();
        for (ViewChange v : ch.views()) {
            if (!known.contains(v.personId())) {
                throw new FormatError("看法写给了不认识的人");
            }
        }
        List<Inner.Concern> activeNow = inner.activeConcerns(me, Integer.MAX_VALUE);
        Set<UUID> changed = new HashSet<>();
        for (ConcernChange c : ch.concerns()) {
            if (c.personId() != null && !known.contains(c.personId())) {
                throw new FormatError("牵挂关于不认识的人");
            }
            if (c.id() != null) {
                if (inner.concern(me, c.id()).isEmpty()) {
                    throw new FormatError("牵挂的 id 不是给出的");
                }
                changed.add(c.id());
            }
        }
        long retained = activeNow.stream().filter(c -> !changed.contains(c.id())).count();
        long proposed = ch.concerns().stream().filter(c -> c.active() && !c.resolved()).count();
        if (retained + proposed > MAX_ACTIVE && retained + proposed > activeNow.size()) {
            throw new FormatError("活跃的牵挂超过 " + MAX_ACTIVE + " 件");
        }
        for (MemoryChange m : ch.memories()) {
            if (m.personId() != null && !known.contains(m.personId())) {
                throw new FormatError("记忆关于不认识的人");
            }
            for (long source : m.sources()) {
                if (traces.find(me, source).isEmpty()) {
                    throw new FormatError("记忆引用的材料 id 不是给出的");
                }
            }
        }
        for (Link l : ch.developments()) {
            var earlier = traces.find(me, l.earlierId());
            var later = traces.find(me, l.laterId());
            if (earlier.isEmpty() || later.isEmpty()) {
                throw new FormatError("developments 引用的材料 id 不是给出的");
            }
            if (earlier.get().createdAt().isAfter(later.get().createdAt())) {
                throw new FormatError("developments 的 earlierId 晚于 laterId");
            }
        }
        return d;
    }

    private void apply(UUID me, Character other, Conversation c, Decision.Changes ch, UUID run) {
        if (ch.selfUnderstanding() != null) {
            inner.addSelf(me, ch.selfUnderstanding(), run);
        }
        for (ViewChange v : ch.views()) {
            inner.addView(me, v.personId(), v.understanding(), run);
        }
        for (ConcernChange x : ch.concerns()) {
            inner.putConcern(me, x.id(), x.personId(), x.cue(), x.content(), x.status(), run);
        }
        for (MemoryChange m : ch.memories()) {
            inner.remember(me, m.cue(), m.content(), null, m.permanent(), m.personId(), m.sources(),
                    List.of(m.personId() != null ? m.personId() : other.id()), "chat", c.id(),
                    "和" + other.name() + "的私聊", run);
        }
        for (Link l : ch.developments()) {
            traces.link(me, l.earlierId(), l.laterId(), l.correction(), run);
        }
    }

    /** 幕后看的“保存了什么”：人名代替 id。 */
    private static Map<String, Object> changes(Decision.Changes ch, Map<UUID, String> names) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (ch.selfUnderstanding() != null) {
            out.put("自我", ch.selfUnderstanding());
        }
        if (!ch.views().isEmpty()) {
            Map<String, String> views = new LinkedHashMap<>();
            ch.views().forEach(v -> views.put(names.getOrDefault(v.personId(), v.personId().toString()), v.understanding()));
            out.put("看法", views);
        }
        if (!ch.concerns().isEmpty()) {
            out.put("牵挂", ch.concerns().stream().map(x -> Map.of("线索", x.cue(), "内容", x.content(),
                    "状态", x.status(), "新的", x.id() == null)).toList());
        }
        if (!ch.memories().isEmpty()) {
            out.put("记忆", ch.memories().stream().map(m -> Map.of("线索", m.cue(), "内容", m.content(),
                    "永久", m.permanent(), "依据", m.sources())).toList());
        }
        if (!ch.developments().isEmpty()) {
            out.put("关联", ch.developments().stream().map(l -> Map.of("较早", l.earlierId(), "较晚", l.laterId(),
                    "纠正", l.correction())).toList());
        }
        return out;
    }

    /** 说出口：一行一条消息，写完一行就交出去。 */
    static final class Expression implements Gateway.Attempt<List<String>> {

        private static final Pattern ACTION = Pattern.compile("^[（(].*[）)]$");

        private final String name;
        private final Sink sink;
        private final StringBuilder pending = new StringBuilder();

        Expression(String name, Sink sink) {
            this.name = name;
            this.sink = sink;
        }

        @Override
        public void content(String delta) {
            pending.append(delta);
            for (int nl = pending.indexOf("\n"); nl >= 0; nl = pending.indexOf("\n")) {
                String line = clean(pending.substring(0, nl), name);
                pending.delete(0, nl + 1);
                if (line != null) {
                    sink.line(line);
                }
            }
        }

        @Override
        public void abandon() {
            pending.setLength(0);
            sink.discard();
        }

        @Override
        public List<String> finish(String text) {
            List<String> out = new ArrayList<>();
            for (String raw : text.split("\\R")) {
                String line = clean(raw, name);
                if (line != null) {
                    out.add(line);
                }
            }
            if (out.isEmpty()) {
                throw new FormatError("没有说出任何话");
            }
            String rest = clean(pending.toString(), name);
            pending.setLength(0);
            if (rest != null) {
                sink.line(rest);
            }
            return out;
        }

        /** 一条消息；空行返回 null。去掉误加的名字前缀和整句的引号；整句是动作或神情不合规。 */
        static String clean(String raw, String name) {
            String line = raw.strip();
            for (String prefix : List.of(name + "（说）：", name + "：", name + ":")) {
                if (line.startsWith(prefix)) {
                    line = line.substring(prefix.length()).strip();
                }
            }
            if (line.length() >= 2 && (line.startsWith("“") && line.endsWith("”")
                    || line.startsWith("\"") && line.endsWith("\""))) {
                line = line.substring(1, line.length() - 1).strip();
            }
            if (line.isEmpty()) {
                return null;
            }
            if (ACTION.matcher(line).matches()) {
                throw new FormatError("私聊里不写动作和神情");
            }
            return line;
        }
    }
}
