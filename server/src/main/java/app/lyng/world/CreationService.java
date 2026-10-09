package app.lyng.world;

import app.lyng.markup.Draft;
import app.lyng.markup.Markup;
import app.lyng.model.Attempt;
import app.lyng.model.CallPurpose;
import app.lyng.model.ModelGateway;
import app.lyng.model.ModelRole;
import app.lyng.prompt.Prompts;
import app.lyng.web.BadRequestException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 一句话创作：生成草稿（不落库）→ 用户在预览页手改 → 确认诞生。 */
@Service
public class CreationService {

    private static final Pattern NAME = Pattern.compile("[^（）：\\s]{1,8}");

    private final ModelGateway gateway;
    private final WorldRepository worlds;

    public CreationService(ModelGateway gateway, WorldRepository worlds) {
        this.gateway = gateway;
        this.worlds = worlds;
    }

    /** worldId 为 null：新世界，世界与人物一起生成；否则只在这个世界里生成人物。 */
    public Draft draft(UUID userId, String request, UUID worldId) {
        if (request == null || request.isBlank()) {
            throw new BadRequestException("请先写一句话");
        }
        World existing = worldId == null ? null : worlds.world(userId, worldId);
        List<String> existingNames = existing == null ? List.of()
                : worlds.charactersOf(userId, existing.id()).stream().map(Character::name).toList();
        return gateway.call(userId, ModelRole.CREATION, new CallPurpose("draft", null, null),
                Prompts.draft(request.strip(), existing == null ? null : existing.publicText(), existingNames),
                () -> Attempt.whole(text -> Markup.parseDraft(text, existing == null)));
    }

    /** 诞生：世界（若是新的）与人物在同一个事务里创建。返回新人物。 */
    @Transactional
    public List<Character> confirm(UUID userId, Draft draft, UUID worldId) {
        validate(draft, worldId == null);
        World world = worldId == null
                ? worlds.insertWorld(userId, draft.world().name().strip(), draft.world().publicText().strip(),
                        draft.world().narratorText().strip())
                : worlds.world(userId, worldId);
        List<String> taken = worlds.charactersOf(userId, world.id()).stream().map(Character::name).toList();
        return draft.characters().stream().map(c -> {
            if (taken.contains(c.name().strip())) {
                throw new BadRequestException("这个世界里已经有叫“" + c.name().strip() + "”的人物");
            }
            return worlds.insertCharacter(userId, world.id(), c.name().strip(), c.profile().strip());
        }).toList();
    }

    private static void validate(Draft draft, boolean newWorld) {
        if (draft == null || draft.characters() == null || draft.characters().isEmpty()) {
            throw new BadRequestException("至少要有一个人物");
        }
        if (newWorld) {
            Draft.WorldDraft w = draft.world();
            if (w == null || blank(w.name()) || blank(w.publicText()) || blank(w.narratorText())) {
                throw new BadRequestException("世界的名字和两段设定都不能为空");
            }
        }
        List<String> names = draft.characters().stream().map(c -> c.name() == null ? "" : c.name().strip()).toList();
        if (names.stream().anyMatch(n -> !NAME.matcher(n).matches()) || names.stream().distinct().count() != names.size()) {
            throw new BadRequestException("人物姓名需为 1 到 8 个字、不含空白与（）：，且不重复");
        }
        if (draft.characters().stream().anyMatch(c -> blank(c.profile()))) {
            throw new BadRequestException("人物的档案不能为空");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
