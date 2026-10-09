package app.lyng.scene;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 人物开口时看到多少过去。
 *
 * @param recentScenes           最近几场已结束的戏给原文，更早的给回想
 * @param maxEarlierRecollections 更早的回想最多给几条（最新的优先）
 */
@ConfigurationProperties("lyng.memory")
public record MemoryProperties(int recentScenes, int maxEarlierRecollections) {}
