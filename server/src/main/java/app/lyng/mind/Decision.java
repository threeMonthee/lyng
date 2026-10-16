package app.lyng.mind;

import app.lyng.model.FormatError;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 人物在心里过一遍之后的私密交接：说什么（或为什么不说）、心声，以及这一轮之后仍有影响的变化。
 * 严格解析：结构不对、字段不认识、类型不对、超出长度都算格式不合规，整份作废重来，不修补也不部分接受。
 * 只容许外面包了一层代码块。
 */
public record Decision(String publicIntent, String silenceReason, String innerVoice, Changes changes) {

    public boolean silent() {
        return publicIntent == null;
    }

    public record Changes(String selfUnderstanding, List<ViewChange> views, List<ConcernChange> concerns,
                          List<MemoryChange> memories, List<Link> developments) {
        static final Changes NONE = new Changes(null, List.of(), List.of(), List.of(), List.of());

        public boolean empty() {
            return selfUnderstanding == null && views.isEmpty() && concerns.isEmpty() && memories.isEmpty()
                    && developments.isEmpty();
        }
    }

    public record ViewChange(UUID personId, String understanding) {}

    /** id 为空是新的一件。 */
    public record ConcernChange(UUID id, UUID personId, String cue, String content, boolean active, boolean resolved) {

        public String status() {
            return resolved ? "resolved" : active ? "active" : "background";
        }
    }

    public record MemoryChange(UUID personId, String cue, String content, boolean permanent, List<Long> sources) {}

    public record Link(long earlierId, long laterId, boolean correction) {}

    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public static Decision parse(String text) {
        JsonNode root;
        try {
            root = STRICT.readTree(unfence(text));
        } catch (JacksonException e) {
            throw new FormatError("不是一个完整的 JSON 对象");
        }
        if (root == null || !root.isObject()) {
            throw new FormatError("不是一个 JSON 对象");
        }
        only(root, "交接", "publicIntent", "silenceReason", "innerVoice", "changes");
        String intent = text(root, "publicIntent", 4000, false);
        String silence = text(root, "silenceReason", 1000, false);
        if ((intent == null) == (silence == null)) {
            throw new FormatError("publicIntent 与 silenceReason 要恰好写一个");
        }
        String inner = text(root, "innerVoice", 600, true);
        JsonNode changes = root.get("changes");
        return new Decision(intent, silence, inner, changes == null ? Changes.NONE : changes(changes));
    }

    private static Changes changes(JsonNode node) {
        if (!node.isObject()) {
            throw new FormatError("changes 要是对象");
        }
        only(node, "changes", "selfUnderstanding", "views", "concerns", "memories", "developments");
        String self = text(node, "selfUnderstanding", 4000, false);
        List<ViewChange> views = new ArrayList<>();
        Set<UUID> viewed = new HashSet<>();
        for (JsonNode v : array(node, "views", 8)) {
            only(v, "views", "personId", "understanding");
            UUID person = id(v, "personId", true);
            if (!viewed.add(person)) {
                throw new FormatError("同一个人的看法写了两次");
            }
            views.add(new ViewChange(person, text(v, "understanding", 8000, true)));
        }
        List<ConcernChange> concerns = new ArrayList<>();
        Set<UUID> revised = new HashSet<>();
        for (JsonNode c : array(node, "concerns", 8)) {
            only(c, "concerns", "id", "personId", "cue", "content", "active", "resolved");
            UUID id = id(c, "id", false);
            if (id != null && !revised.add(id)) {
                throw new FormatError("同一件牵挂改了两次");
            }
            boolean active = bool(c, "active", true);
            boolean resolved = bool(c, "resolved", true);
            if (active && resolved) {
                throw new FormatError("牵挂不能既活跃又已结束");
            }
            concerns.add(new ConcernChange(id, id(c, "personId", false), text(c, "cue", 160, true),
                    text(c, "content", 2000, true), active, resolved));
        }
        List<MemoryChange> memories = new ArrayList<>();
        for (JsonNode m : array(node, "memories", 4)) {
            only(m, "memories", "personId", "cue", "content", "permanent", "sources");
            List<Long> sources = new ArrayList<>();
            for (JsonNode s : array(m, "sources", 8)) {
                sources.add(number(s, "sources"));
            }
            memories.add(new MemoryChange(id(m, "personId", false), text(m, "cue", 160, true),
                    text(m, "content", 4000, true), bool(m, "permanent", true), List.copyOf(sources)));
        }
        List<Link> links = new ArrayList<>();
        for (JsonNode d : array(node, "developments", 8)) {
            only(d, "developments", "earlierId", "laterId", "correction");
            JsonNode earlier = d.get("earlierId");
            JsonNode later = d.get("laterId");
            if (earlier == null || later == null) {
                throw new FormatError("developments 要写 earlierId 和 laterId");
            }
            long a = number(earlier, "earlierId");
            long b = number(later, "laterId");
            if (a == b) {
                throw new FormatError("developments 的两端是同一条");
            }
            links.add(new Link(a, b, bool(d, "correction", false)));
        }
        return new Changes(self, List.copyOf(views), List.copyOf(concerns), List.copyOf(memories), List.copyOf(links));
    }

    /** 去掉外面可能包着的一层代码块。 */
    static String unfence(String text) {
        String t = text.strip();
        if (t.startsWith("```")) {
            int open = t.indexOf('\n');
            if (open > 0 && t.endsWith("```") && t.length() > open + 3) {
                return t.substring(open + 1, t.length() - 3).strip();
            }
        }
        return t;
    }

    private static void only(JsonNode node, String where, String... allowed) {
        if (!node.isObject()) {
            throw new FormatError(where + " 里的一项不是对象");
        }
        Set<String> names = Set.of(allowed);
        for (Iterator<String> it = node.propertyNames().iterator(); it.hasNext(); ) {
            String name = it.next();
            if (!names.contains(name)) {
                throw new FormatError(where + " 里有不认识的字段 " + name);
            }
        }
    }

    private static String text(JsonNode node, String field, int max, boolean required) {
        JsonNode v = node.get(field);
        if (v == null) {
            if (required) {
                throw new FormatError("缺少 " + field);
            }
            return null;
        }
        if (!v.isString() || v.asString().isBlank()) {
            throw new FormatError(field + " 要是非空的文字");
        }
        if (v.asString().length() > max) {
            throw new FormatError(field + " 太长");
        }
        return v.asString().strip();
    }

    private static boolean bool(JsonNode node, String field, boolean required) {
        JsonNode v = node.get(field);
        if (v == null) {
            if (required) {
                throw new FormatError("缺少 " + field);
            }
            return false;
        }
        if (!v.isBoolean()) {
            throw new FormatError(field + " 要是 true 或 false");
        }
        return v.asBoolean();
    }

    private static UUID id(JsonNode node, String field, boolean required) {
        JsonNode v = node.get(field);
        if (v == null) {
            if (required) {
                throw new FormatError("缺少 " + field);
            }
            return null;
        }
        try {
            return UUID.fromString(v.isString() ? v.asString() : "");
        } catch (IllegalArgumentException e) {
            throw new FormatError(field + " 不是给出的 id");
        }
    }

    /** 档案里的行号：整数；写成数字字样的文字也认。 */
    private static long number(JsonNode v, String field) {
        if (v.isIntegralNumber()) {
            return v.asLong();
        }
        if (v.isString() && v.asString().strip().matches("\\d{1,18}")) {
            return Long.parseLong(v.asString().strip());
        }
        throw new FormatError(field + " 要是材料的 id");
    }

    private static List<JsonNode> array(JsonNode node, String field, int max) {
        JsonNode v = node.get(field);
        if (v == null) {
            return List.of();
        }
        if (!v.isArray()) {
            throw new FormatError(field + " 要是数组");
        }
        if (v.size() > max) {
            throw new FormatError(field + " 最多 " + max + " 项");
        }
        List<JsonNode> out = new ArrayList<>();
        v.forEach(out::add);
        return out;
    }
}
