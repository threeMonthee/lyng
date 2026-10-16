package app.lyng.mind;

import app.lyng.model.Embedder;
import app.lyng.store.Traces;
import app.lyng.store.Traces.Trace;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 让亲历可以被想起，而不替人物决定它意味着什么、要不要说出来：从人物自己的亲历档案里拿出近期的与眼前相关的材料，
 * 各带一页人物自己提出过的直接关联，和几条较晚的相关候选。两者都尚待人物自己判断。
 * 向量服务不可用时只少了“相关”的那部分，近期的照样给。
 */
@Service
public class Recollection {

    private static final Logger log = LoggerFactory.getLogger(Recollection.class);

    static final int RECENT_SCAN = 12;
    static final Duration RECENT_WINDOW = Duration.ofHours(48);
    static final int RECENT_KEPT = 6;
    static final int SEMANTIC_ROOTS = 4;
    static final int ROOTS = 10;
    static final int QUERY_CHARACTERS = 16_000;
    static final int MAX_DEVELOPMENT_OFFSET = 1_000_000;
    /** 自动附上的材料里，每条原文与线索的长度。 */
    static final int SUMMARY_CHARACTERS = 600;
    /** 人物特意去读一条时的长度。 */
    static final int FULL_CHARACTERS = 12_000;
    /** 自动附上的全部材料，以及每一类线索各自的份额。 */
    static final int MATERIAL_CHARACTERS = 12_000;
    static final int LEAD_CHARACTERS = 2_400;
    static final int DEVELOPMENTS_PER_PAGE = 6;
    static final int LATER_SCAN = 10;
    static final int LATER_KEPT = 3;

    /** 一条材料。content 为空表示同 id 的原文已在别处给出；excerpt 表示还有原文没给完。 */
    public record Excerpt(long id, String source, Instant occurredAt, String content, boolean excerpt) {}

    public record Bundle(Excerpt experience, List<Excerpt> suggestedDevelopments, boolean moreDevelopments,
                         Integer nextDevelopmentOffset, List<Excerpt> possibleLaterExperiences) {}

    private final Traces traces;
    private final Embedder embedder;
    private final Recall recall;

    public Recollection(Traces traces, Embedder embedder, Recall recall) {
        this.traces = traces;
        this.embedder = embedder;
        this.recall = recall;
    }

    /**
     * 附上别处近期的材料、与此刻相关的旧材料，以及眼前这段话里已有的线索。conversation 是已在上下文里的行
     * （按先后），latest 是最近一行的原文；没有话时按仍活跃的牵挂去找。
     */
    public List<Bundle> enrich(UUID characterId, List<Long> conversation, String latest, List<String> concerns,
                               Instant now) {
        Set<Long> local = new HashSet<>(conversation);
        Map<Long, Trace> roots = new LinkedHashMap<>();
        Instant since = now.minus(RECENT_WINDOW);
        traces.newest(characterId, RECENT_SCAN).stream()
                .filter(t -> !local.contains(t.id()) && !t.createdAt().isBefore(since))
                .limit(RECENT_KEPT)
                .forEach(t -> roots.putIfAbsent(t.id(), t));
        String query = latest != null ? latest : String.join("\n", concerns);
        query = query.substring(0, Math.min(query.length(), QUERY_CHARACTERS));
        if (!query.isBlank()) {
            try {
                search(characterId, query, SEMANTIC_ROOTS).forEach(t -> roots.putIfAbsent(t.id(), t));
            } catch (RuntimeException e) {
                log.warn("想起相关的事失败（人物 {}）：{}", characterId, e.getMessage());
            }
        }
        List<Bundle> bundles = new ArrayList<>(developmentsOf(characterId, conversation));
        roots.values().stream().filter(t -> !local.contains(t.id())).limit(ROOTS)
                .forEach(t -> bundles.add(bundle(characterId, t, SUMMARY_CHARACTERS, 0)));
        return fitBudget(bundles, local);
    }

    /** 按语义找相关的亲历与记忆。 */
    public List<Trace> search(UUID characterId, String query, int limit) {
        if (query == null || query.isBlank() || query.length() > QUERY_CHARACTERS) {
            throw new IllegalArgumentException("要写清想找什么");
        }
        recall.index(characterId);
        return traces.related(characterId, embedder.embed(List.of(query), true).getFirst(), limit);
    }

    public Optional<Bundle> read(UUID characterId, long id, int offset) {
        if (offset < 0 || offset > MAX_DEVELOPMENT_OFFSET) {
            throw new IllegalArgumentException("翻页的位置不对");
        }
        return traces.find(characterId, id).map(t -> bundle(characterId, t, FULL_CHARACTERS, offset));
    }

    public Optional<Bundle> readSummary(UUID characterId, long id) {
        return traces.find(characterId, id).map(t -> bundle(characterId, t, SUMMARY_CHARACTERS, 0));
    }

    /** 已在上下文里的行身上的线索。原文不重复给，线索也不挤占回忆的份额。 */
    private List<Bundle> developmentsOf(UUID characterId, List<Long> conversation) {
        List<Bundle> out = new ArrayList<>();
        for (Long id : conversation.reversed()) {
            readSummary(characterId, id).filter(b -> !b.suggestedDevelopments().isEmpty()
                    || !b.possibleLaterExperiences().isEmpty()).ifPresent(b -> {
                Excerpt root = b.experience();
                out.add(new Bundle(new Excerpt(root.id(), root.source(), root.occurredAt(), "", true),
                        b.suggestedDevelopments(), b.moreDevelopments(), b.nextDevelopmentOffset(),
                        b.possibleLaterExperiences()));
            });
        }
        return out;
    }

    /** 一条亲历，带一页人物提出过的直接关联，和几条据它的向量补查到的较晚候选。 */
    private Bundle bundle(UUID characterId, Trace root, int characters, int offset) {
        int leadCharacters = Math.min(characters, SUMMARY_CHARACTERS);
        List<Trace> developments = traces.developments(characterId, root.id(), offset, DEVELOPMENTS_PER_PAGE + 1);
        Set<Long> linked = new HashSet<>();
        developments.forEach(d -> linked.add(d.id()));
        boolean more = developments.size() > DEVELOPMENTS_PER_PAGE;
        List<Excerpt> later = traces.relatedLater(characterId, root.id(), LATER_SCAN).stream()
                .filter(t -> !linked.contains(t.id())).limit(LATER_KEPT).map(t -> excerpt(t, leadCharacters))
                .toList();
        return new Bundle(excerpt(root, characters),
                developments.stream().limit(DEVELOPMENTS_PER_PAGE).map(d -> excerpt(d, leadCharacters)).toList(),
                more, more ? offset + DEVELOPMENTS_PER_PAGE : null, later);
    }

    /** 整条原文放进总份额，再在各自的份额里放两类线索。别处已给过的原文换成只给 id。 */
    static List<Bundle> fitBudget(List<Bundle> bundles, Set<Long> local) {
        List<Bundle> kept = new ArrayList<>();
        Map<Long, Bundle> originals = new HashMap<>();
        Set<Long> rendered = new HashSet<>(local);
        int used = 0;
        for (Bundle original : bundles) {
            Excerpt root = referenceIfRendered(original.experience(), rendered);
            if (used + root.content().length() > MATERIAL_CHARACTERS) {
                continue;
            }
            used += root.content().length();
            rendered.add(root.id());
            originals.put(root.id(), original);
            kept.add(new Bundle(root, List.of(), original.moreDevelopments(), original.nextDevelopmentOffset(),
                    List.of()));
        }
        // 每类线索有自己的上限：人物提出的关联再多，也挤不掉独立补查到的候选
        used = fitLeads(kept, originals, rendered, used, true);
        fitLeads(kept, originals, rendered, used, false);
        return kept;
    }

    private static int fitLeads(List<Bundle> kept, Map<Long, Bundle> originals, Set<Long> rendered, int used,
                                boolean developments) {
        int remaining = Math.min(LEAD_CHARACTERS, MATERIAL_CHARACTERS - used);
        for (int i = 0; i < kept.size(); i++) {
            Bundle current = kept.get(i);
            Bundle original = originals.get(current.experience().id());
            List<Excerpt> source = developments ? original.suggestedDevelopments() : original.possibleLaterExperiences();
            List<Excerpt> fitted = new ArrayList<>();
            for (Excerpt item : source) {
                Excerpt e = referenceIfRendered(item, rendered);
                if (e.content().length() > remaining) {
                    break;
                }
                remaining -= e.content().length();
                used += e.content().length();
                rendered.add(e.id());
                fitted.add(e);
            }
            // 没放下的关联还能从第一页翻到
            boolean omitted = developments && fitted.size() < source.size();
            kept.set(i, new Bundle(current.experience(), developments ? fitted : current.suggestedDevelopments(),
                    current.moreDevelopments() || omitted, omitted ? Integer.valueOf(0) : current.nextDevelopmentOffset(),
                    developments ? current.possibleLaterExperiences() : fitted));
        }
        return used;
    }

    private static Excerpt referenceIfRendered(Excerpt e, Set<Long> rendered) {
        return rendered.contains(e.id()) ? new Excerpt(e.id(), e.source(), e.occurredAt(), "", false) : e;
    }

    static Excerpt excerpt(Trace t, int characters) {
        String text = t.text();
        return new Excerpt(t.id(), source(t), t.createdAt(), text.substring(0, Math.min(characters, text.length())),
                text.length() > characters);
    }

    /** 材料出自哪里：哪段私聊（带日期）、哪一场，或自己形成的记忆。 */
    static String source(Trace t) {
        return t.sourceKind().equals("chat") ? t.sourceLabel() + "（" + Perceive.date(t.createdAt()) + "）"
                : t.sourceLabel();
    }
}
