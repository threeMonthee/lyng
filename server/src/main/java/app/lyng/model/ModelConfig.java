package app.lyng.model;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** 按环境选真实服务或确定性替身。 */
@Configuration
public class ModelConfig {

    @Bean
    ChatModel chatModel(ModelProperties props, JsonMapper json) {
        return switch (props.backend()) {
            case "openrouter" -> new OpenRouterModel(props, json);
            case "fake" -> new FakeModel(props.fakeDelayMs());
            default -> throw new IllegalStateException("未知的 LYNG_MODEL_BACKEND：" + props.backend());
        };
    }

    @Bean
    Embedder embedder(EmbeddingProperties props, JsonMapper json) {
        return switch (props.backend()) {
            case "dashscope" -> new DashScopeEmbedder(props, json);
            case "fake" -> new FakeEmbedder();
            default -> throw new IllegalStateException("未知的 LYNG_EMBEDDING_BACKEND：" + props.backend());
        };
    }
}
