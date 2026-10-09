package app.lyng.model;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class ModelConfig {

    @Bean
    ChatModel chatModel(ModelProperties props, JsonMapper json,
                        @Value("${lyng.fake.chunk-chars}") int chunkChars,
                        @Value("${lyng.fake.chunk-delay-ms}") long chunkDelayMs) {
        return switch (props.backend()) {
            case "openrouter" -> new OpenRouterChatModel(props, json);
            case "fake" -> new FakeChatModel(chunkChars, chunkDelayMs);
            default -> throw new IllegalStateException("未知的模型后端：" + props.backend() + "（openrouter 或 fake）");
        };
    }
}
