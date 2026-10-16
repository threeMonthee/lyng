package app.lyng.mind;

import app.lyng.mind.Prompts.Recalled;
import app.lyng.model.Embedder;
import app.lyng.store.Inner;
import app.lyng.store.Inner.Memory;
import app.lyng.store.Traces;
import app.lyng.store.Traces.Trace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 想起过去：给经历建立向量索引，按眼前情境找相近的记忆与原话。召回允许失败——
 * 向量服务不可用时只少了“想起”，经历原文照样在。
 */
@Service
public class Recall {

    private static final Logger log = LoggerFactory.getLogger(Recall.class);

    static final int RECENT_MEMORIES = 3;
    static final int SIMILAR_MEMORIES = 4;
    static final int SIMILAR_TRACES = 3;
    static final int TOOL_MEMORIES = 4;
    static final int TOOL_TRACES = 4;
    static final int INDEX_BATCH = 60;

    private final Inner inner;
    private final Traces traces;
    private final Embedder embedder;

    public Recall(Inner inner, Traces traces, Embedder embedder) {
        this.inner = inner;
        this.traces = traces;
        this.embedder = embedder;
    }

    /** 给这个人物还没有向量的经历与记忆建索引。失败只记日志，下次再补。 */
    public void index(UUID characterId) {
        try {
            for (List<Traces.Unindexed> batch = traces.unindexed(characterId, INDEX_BATCH); !batch.isEmpty();
                 batch = traces.unindexed(characterId, INDEX_BATCH)) {
                List<float[]> vectors = embedder.embed(batch.stream().map(Traces.Unindexed::text).toList(), false);
                for (int i = 0; i < batch.size(); i++) {
                    traces.index(batch.get(i).id(), vectors.get(i));
                }
            }
            List<Inner.Unindexed> memories = inner.unindexedMemories(characterId);
            if (!memories.isEmpty()) {
                List<float[]> vectors = embedder.embed(memories.stream().map(Inner.Unindexed::text).toList(), false);
                for (int i = 0; i < memories.size(); i++) {
                    inner.indexMemory(memories.get(i).id(), vectors.get(i));
                }
            }
        } catch (RuntimeException e) {
            log.warn("建立索引失败（人物 {}）：{}", characterId, e.getMessage());
        }
    }

    /** 最近的几条记忆：不管眼前是什么，刚经历过的事总在心上。 */
    public List<Memory> recentMemories(UUID characterId) {
        return inner.memories(characterId, RECENT_MEMORIES, 0);
    }

    public static Recalled recalled(Memory m) {
        return new Recalled(m.sourceLabel(), m.text());
    }

    /**
     * 按眼前情境召回：相近的记忆（排除已经给出的），以及相近的原话片段（排除当前来源里已在上下文的部分）。
     */
    public List<Recalled> around(UUID characterId, String situation, List<UUID> shownMemories, UUID currentSource,
                                 long currentFromRef) {
        if (situation == null || situation.isBlank()) {
            return List.of();
        }
        float[] query;
        try {
            index(characterId);
            query = embedder.embed(List.of(situation), true).getFirst();
        } catch (RuntimeException e) {
            log.warn("召回失败（人物 {}）：{}", characterId, e.getMessage());
            return List.of();
        }
        List<Recalled> out = new ArrayList<>();
        inner.similarMemories(characterId, query, SIMILAR_MEMORIES, shownMemories).forEach(m -> out.add(recalled(m)));
        out.addAll(snippets(characterId, traces.similar(characterId, query, currentSource, currentFromRef, SIMILAR_TRACES)));
        return out;
    }

    /** 人物自己去翻（工具 recall）。返回给模型看的文字。 */
    public String search(UUID characterId, String query, UUID currentSource, long currentFromRef) {
        if (query == null || query.isBlank()) {
            return "要写清想找什么。";
        }
        index(characterId);
        float[] vector = embedder.embed(List.of(query), true).getFirst();
        List<Recalled> found = new ArrayList<>();
        inner.similarMemories(characterId, vector, TOOL_MEMORIES, List.of()).forEach(m -> found.add(recalled(m)));
        found.addAll(snippets(characterId, traces.similar(characterId, vector, currentSource, currentFromRef, TOOL_TRACES)));
        if (found.isEmpty()) {
            return "没有翻到相关的经历。";
        }
        return found.stream().map(r -> "· " + r.source() + "：" + r.text()).collect(Collectors.joining("\n"));
    }

    /** 命中的原话各带前后两行，合并重叠的片段，按来源分组。 */
    private List<Recalled> snippets(UUID characterId, List<Long> hits) {
        Map<Long, Trace> lines = new LinkedHashMap<>();
        for (long id : hits) {
            traces.window(characterId, id, 2, 2).forEach(t -> lines.putIfAbsent(t.id(), t));
        }
        List<Trace> sorted = lines.values().stream().sorted((a, b) -> Long.compare(a.id(), b.id())).toList();
        List<Recalled> out = new ArrayList<>();
        List<String> current = new ArrayList<>();
        Trace previous = null;
        for (Trace t : sorted) {
            boolean continues = previous != null && previous.sourceId().equals(t.sourceId());
            if (!continues && !current.isEmpty()) {
                out.add(new Recalled(label(previous), "\n  " + String.join("\n  ", current)));
                current = new ArrayList<>();
            }
            current.add(t.text());
            previous = t;
        }
        if (!current.isEmpty()) {
            out.add(new Recalled(label(previous), "\n  " + String.join("\n  ", current)));
        }
        return out;
    }

    /** 私聊的片段带上日期，人物才知道是多久以前的事。 */
    private static String label(Trace t) {
        return t.sourceKind().equals("chat") ? t.sourceLabel() + "（" + Perceive.date(t.createdAt()) + "）" : t.sourceLabel();
    }
}
