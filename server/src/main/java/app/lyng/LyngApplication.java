package app.lyng;

import app.lyng.model.ChatModel;
import app.lyng.model.FakeModel;
import app.lyng.model.ModelProperties;
import app.lyng.model.OpenRouter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LyngApplication {

    public static void main(String[] args) throws IOException {
        // SQLite 不会自己建目录
        Path db = Path.of(System.getenv().getOrDefault("LYNG_DB", "../data/lyng.db")).toAbsolutePath();
        Files.createDirectories(db.getParent());
        SpringApplication.run(LyngApplication.class, args);
    }

    @Bean
    ChatModel chatModel(ModelProperties props, JsonMapper json) {
        return switch (props.backend()) {
            case "openrouter" -> new OpenRouter(props, json);
            case "fake" -> new FakeModel(props.fakeDelayMs());
            default -> throw new IllegalStateException("LYNG_MODEL_BACKEND 只能是 openrouter 或 fake：" + props.backend());
        };
    }
}
