package app.lyng.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class TestModelConfig {

    @Bean
    @Primary
    ScriptedChatModel scriptedChatModel() {
        return new ScriptedChatModel();
    }
}
