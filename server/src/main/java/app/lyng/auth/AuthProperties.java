package app.lyng.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 单个本地账号。口令只从环境读（仓库根 .env 的 LYNG_PASSWORD），启动时同步成哈希。 */
@ConfigurationProperties("lyng.auth")
public record AuthProperties(String user, String password) {}
