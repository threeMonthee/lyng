package app.lyng.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lyng.embedding")
public record EmbeddingProperties(String backend, String apiKey, String baseUrl, String model, int dimension) {}
