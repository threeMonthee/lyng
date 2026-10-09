package app.lyng;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LyngApplication {
    public static void main(String[] args) {
        SpringApplication.run(LyngApplication.class, args);
    }
}
