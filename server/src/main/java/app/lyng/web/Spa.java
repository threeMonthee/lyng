package app.lyng.web;

import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * 页面由同一个服务提供：构建好的前端在 client/dist（LYNG_CLIENT_DIST 可改）。
 * 不是文件的路径都回到 index.html，由前端路由处理；/api 不经过这里。
 */
@Configuration
public class Spa implements WebMvcConfigurer {

    private final String dist;

    public Spa(@Value("${lyng.client-dist:../client/dist}") String dist) {
        this.dist = dist.endsWith("/") ? dist : dist + "/";
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("file:" + dist)
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String path, Resource location) throws IOException {
                        if (path.startsWith("api/")) {
                            return null;
                        }
                        Resource found = location.createRelative(path);
                        if (found.exists() && found.isReadable()) {
                            return found;
                        }
                        Resource index = location.createRelative("index.html");
                        return index.exists() ? index : null;
                    }
                });
    }
}
