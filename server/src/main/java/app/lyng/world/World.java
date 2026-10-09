package app.lyng.world;

import java.util.UUID;

/** 世界。诞生后不改。narratorText 是只有叙事者知道的设定。 */
public record World(UUID id, String name, String publicText, String narratorText) {}
