package app.lyng.web;

import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.ConfigurableServletWebServerFactory;
import org.springframework.boot.web.server.MimeMappings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class WebConfig {

    /** PWA 的 manifest 要以 application/manifest+json 提供，浏览器才认它可安装。 */
    @Bean
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> manifestMimeType() {
        return factory -> {
            MimeMappings mappings = new MimeMappings(MimeMappings.DEFAULT);
            mappings.add("webmanifest", "application/manifest+json");
            factory.setMimeMappings(mappings);
        };
    }
}
