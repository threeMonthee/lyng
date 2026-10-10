package app.lyng.story;

import app.lyng.ApiError;
import app.lyng.model.Attempt;
import app.lyng.model.ChatModel.Purpose;
import app.lyng.model.Gateway;
import app.lyng.model.Role;
import app.lyng.store.Store;
import app.lyng.store.Store.Character;
import app.lyng.store.Store.World;
import app.lyng.story.Markup.Draft;
import app.lyng.story.Markup.DraftCharacter;
import app.lyng.story.Markup.DraftWorld;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 一句话创作：生成草稿（不落库）→ 用户在预览页手改 → 确认诞生（一个事务）。 */
@Service
public class Creation {

    private final Gateway gateway;
    private final Store store;
    private final TransactionTemplate tx;

    public Creation(Gateway gateway, Store store, TransactionTemplate tx) {
        this.gateway = gateway;
        this.store = store;
        this.tx = tx;
    }

    /** worldId 为空：新世界，世界与人物一起写；否则只在这个世界里写人物。 */
    public Draft draft(String request, String worldId) {
        if (request == null || request.isBlank()) {
            throw ApiError.badRequest("先写一句话");
        }
        World world = worldId == null ? null : store.world(worldId);
        List<String> names = world == null ? List.of() : store.charactersOf(world.id()).stream().map(Character::name).toList();
        return gateway.call(Role.CREATION, new Purpose("draft", world == null ? null : world.name(), null),
                Prompts.draft(request.strip(), world == null ? null : world.publicText(), names),
                () -> Attempt.whole(text -> Markup.parseDraft(text, world == null)));
    }

    public record Born(String worldId, List<String> characterIds) {}

    /** 诞生：世界（若是新的）与人物在同一个事务里创建。此后档案不再改写（圣经 §3.2）。 */
    public Born birth(Draft draft, String worldId) {
        validate(draft, worldId == null);
        return tx.execute(status -> {
            World world;
            if (worldId == null) {
                DraftWorld w = draft.world();
                if (store.worldNameTaken(w.name().strip())) {
                    throw ApiError.badRequest("已经有叫“" + w.name().strip() + "”的世界了，换一个名字，或者在那个世界里添人物");
                }
                world = store.insertWorld(w.name().strip(), w.publicText().strip(), w.narratorText().strip());
            } else {
                world = store.world(worldId);
            }
            List<String> taken = store.charactersOf(world.id()).stream().map(Character::name).toList();
            List<String> ids = draft.characters().stream().map(c -> {
                if (taken.contains(c.name().strip())) {
                    throw ApiError.badRequest("这个世界里已经有叫“" + c.name().strip() + "”的人物了");
                }
                return store.insertCharacter(world.id(), c.name().strip(), c.profile().strip()).id();
            }).toList();
            return new Born(world.id(), ids);
        });
    }

    private static void validate(Draft draft, boolean newWorld) {
        if (draft == null || draft.characters() == null || draft.characters().isEmpty()) {
            throw ApiError.badRequest("至少要有一个人物");
        }
        if (newWorld) {
            DraftWorld w = draft.world();
            if (w == null || blank(w.name()) || blank(w.publicText()) || blank(w.narratorText())) {
                throw ApiError.badRequest("世界的名字和两段设定都不能为空");
            }
        }
        List<String> names = draft.characters().stream().map(c -> c.name() == null ? "" : c.name().strip()).toList();
        if (names.stream().anyMatch(n -> !Markup.CHARACTER_NAME.matcher(n).matches()) || names.stream().distinct().count() != names.size()) {
            throw ApiError.badRequest("人物姓名要 1 到 8 个字，不含空白与（）：，且不重复");
        }
        if (draft.characters().stream().map(DraftCharacter::profile).anyMatch(Creation::blank)) {
            throw ApiError.badRequest("人物的档案不能为空");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
