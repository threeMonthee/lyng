package app.lyng.world;

import java.util.UUID;

/** 人物。档案在诞生时定下，此后不改写（圣经 §3.2）。 */
public record Character(UUID id, UUID homeWorldId, String name, String profile) {}
