package app.lyng.web;

import app.lyng.auth.AuthFilter;
import app.lyng.model.ModelCall;
import app.lyng.model.ModelCallRepository;
import app.lyng.model.ModelProperties;
import app.lyng.model.ModelRole;
import app.lyng.model.ModelSettings;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class SettingsController {

    record Settings(Map<String, String> models, String defaultModel, String preferredProvider) {}

    record Usage(BigDecimal totalCost, Double budget, List<ModelCall> recent) {}

    private final ModelSettings settings;
    private final ModelProperties props;
    private final ModelCallRepository calls;

    SettingsController(ModelSettings settings, ModelProperties props, ModelCallRepository calls) {
        this.settings = settings;
        this.props = props;
        this.calls = calls;
    }

    @GetMapping("/settings")
    Settings get(@RequestAttribute(AuthFilter.USER) UUID userId) {
        return view(userId);
    }

    /** 键是 character / narrator / creation / recall；值写 模型ID 或 模型ID@推理设置，空表示恢复默认。 */
    @PutMapping("/settings")
    Settings put(@RequestAttribute(AuthFilter.USER) UUID userId, @RequestBody Map<String, String> models) {
        models.forEach((key, spec) -> settings.save(userId, role(key), spec));
        return view(userId);
    }

    @GetMapping("/usage")
    Usage usage(@RequestAttribute(AuthFilter.USER) UUID userId) {
        return new Usage(calls.totalCost(userId), props.budgetUsd(), calls.recent(userId, 50));
    }

    private Settings view(UUID userId) {
        Map<String, String> models = new LinkedHashMap<>();
        settings.all(userId).forEach((role, choice) -> models.put(role.key(), choice.spec()));
        return new Settings(models, props.defaultChoice().spec(), props.preferredProvider());
    }

    private static ModelRole role(String key) {
        for (ModelRole role : ModelRole.values()) {
            if (role.key().equals(key)) {
                return role;
            }
        }
        throw new IllegalArgumentException("未知角色：" + key);
    }
}
