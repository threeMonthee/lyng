package app.lyng;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LyngApplication {

    /** 现阶段只有一个用户；所有表仍按它隔离，不堵 C 端的路。 */
    public static final String USER = "me";

    public static void main(String[] args) {
        SpringApplication.run(LyngApplication.class, args);
    }
}
