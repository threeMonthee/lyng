package app.lyng.scene;

import app.lyng.event.SceneEvent;
import app.lyng.model.ModelGateway;
import app.lyng.prompt.Prompts;
import app.lyng.web.BadRequestException;
import app.lyng.web.ConflictException;
import app.lyng.world.Character;
import app.lyng.world.World;
import app.lyng.world.WorldRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/**
 * 一场戏的对外入口：创建、读取，以及会跑模型的四个动作（开场、一轮、重试、收尾）。
 *
 * <p>会跑模型的动作分两步：先同步校验并占住场锁（失败就是普通的 HTTP 错误），返回的 Runnable 再到后台线程里执行。
 * 一轮开始后即使客户端断开也会在服务端跑完并落库，刷新后从数据库恢复。
 */
@Service
public class SceneService {

    public static final int MAX_ACTION_CHARS = 2000;

    public record CreateScene(UUID possessedId, UUID otherId, String tone, String setup) {}

    /** 一场戏的全部：元信息、阵容、事件，以及还没解决的失败。 */
    public record Detail(Scene scene, SceneCast cast, List<SceneEvent> events, List<SceneEvent> unresolved, boolean busy) {}

    public record Summary(Scene scene, String worldName, String possessed, List<String> others) {}

    private final SceneRepository scenes;
    private final WorldRepository worlds;
    private final ModelGateway gateway;
    private final ContextAssembler contexts;
    private final RecallService recalls;
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();

    public SceneService(SceneRepository scenes, WorldRepository worlds, ModelGateway gateway, ContextAssembler contexts,
                        RecallService recalls) {
        this.scenes = scenes;
        this.worlds = worlds;
        this.gateway = gateway;
        this.contexts = contexts;
        this.recalls = recalls;
    }

    public Scene create(UUID userId, CreateScene cmd) {
        if (cmd.possessedId() == null || cmd.otherId() == null || cmd.possessedId().equals(cmd.otherId())) {
            throw new BadRequestException("要选两个不同的人物：你附身的，和在场的另一位");
        }
        if (!Set.of(Prompts.TONE_DAILY, Prompts.TONE_ADVENTURE).contains(cmd.tone())) {
            throw new BadRequestException("基调只能是 " + Prompts.TONE_DAILY + " 或 " + Prompts.TONE_ADVENTURE);
        }
        if (cmd.setup() == null || cmd.setup().isBlank() || cmd.setup().length() > MAX_ACTION_CHARS) {
            throw new BadRequestException("请用一两句话写下开场处境");
        }
        Character possessed = worlds.character(userId, cmd.possessedId());
        Character other = worlds.character(userId, cmd.otherId());
        if (!possessed.homeWorldId().equals(other.homeWorldId())) {
            throw new BadRequestException("两个人物不在同一个世界里（穿越还没有做）");
        }
        return scenes.insert(userId, possessed.homeWorldId(), possessed.id(), cmd.tone(), Prompts.SCALE_MILD,
                cmd.setup().strip(), List.of(possessed.id(), other.id()));
    }

    public Detail detail(UUID userId, UUID sceneId) {
        Scene scene = scenes.scene(userId, sceneId);
        List<SceneEvent> events = scenes.events(userId, sceneId);
        return new Detail(scene, cast(userId, scene), events, SceneStatus.unresolvedFailures(events), running.contains(sceneId));
    }

    public List<Summary> list(UUID userId) {
        Map<UUID, String> worldNames = new HashMap<>();
        worlds.worlds(userId).forEach(w -> worldNames.put(w.id(), w.name()));
        Map<UUID, String> names = new HashMap<>();
        worlds.characters(userId).forEach(c -> names.put(c.id(), c.name()));
        return scenes.scenes(userId).stream().map(s -> new Summary(s, worldNames.get(s.worldId()),
                names.get(s.possessedId()),
                scenes.characterIds(userId, s.id()).stream().filter(id -> !id.equals(s.possessedId())).map(names::get).toList()))
                .toList();
    }

    // ---- 会跑模型的动作 ----

    public Runnable open(UUID userId, UUID sceneId, RoundListener listener) {
        return begin(userId, sceneId, listener, SceneRun::checkOpen, SceneRun::open);
    }

    public Runnable act(UUID userId, UUID sceneId, String action, String text, RoundListener listener) {
        validateAction(action, text);
        return begin(userId, sceneId, listener, SceneRun::checkAct,
                run -> run.act(action, SceneEvent.WAIT.equals(action) ? "" : text.strip()));
    }

    public Runnable retry(UUID userId, UUID sceneId, RoundListener listener) {
        return begin(userId, sceneId, listener, SceneRun::checkRetry, SceneRun::retry);
    }

    public Runnable end(UUID userId, UUID sceneId, boolean closing, RoundListener listener) {
        return begin(userId, sceneId, listener, run -> run.checkEnd(closing), run -> run.end(closing));
    }

    private static void validateAction(String action, String text) {
        if (!Set.of(SceneEvent.SAY, SceneEvent.DO, SceneEvent.WAIT).contains(action)) {
            throw new BadRequestException("动作只能是 说、做 或 静候");
        }
        if (!SceneEvent.WAIT.equals(action) && (text == null || text.isBlank() || text.length() > MAX_ACTION_CHARS)) {
            throw new BadRequestException("请写下你要" + action + "的内容（最多 " + MAX_ACTION_CHARS + " 字）");
        }
    }

    private Runnable begin(UUID userId, UUID sceneId, RoundListener listener, Consumer<SceneRun> check,
                           Consumer<SceneRun> action) {
        Scene scene = scenes.scene(userId, sceneId);
        if (scene.ended()) {
            throw new ConflictException("这一场已经结束了");
        }
        if (!running.add(sceneId)) {
            throw new ConflictException("这一场正在进行一轮");
        }
        try {
            SceneRun run = new SceneRun(userId, scene, cast(userId, scene), gateway, scenes, contexts, recalls, listener);
            check.accept(run);
            return () -> {
                try {
                    action.accept(run);
                } finally {
                    running.remove(sceneId);
                }
            };
        } catch (RuntimeException e) {
            running.remove(sceneId);
            throw e;
        }
    }

    private SceneCast cast(UUID userId, Scene scene) {
        World world = worlds.world(userId, scene.worldId());
        Character possessed = worlds.character(userId, scene.possessedId());
        List<Character> ai = scenes.characterIds(userId, scene.id()).stream()
                .filter(id -> !id.equals(scene.possessedId())).map(id -> worlds.character(userId, id)).toList();
        return new SceneCast(world, possessed, ai);
    }
}
