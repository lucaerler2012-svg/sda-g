package com.multiraid;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

/**
 * Sucht Entity-Typen ueber ihren Registry-Namen (z. B. "pillager").
 * Das ist unabhaengig davon, in welcher Klasse Minecraft die Konstanten ablegt.
 */
final class RaidMobs {
    private RaidMobs() {
    }

    /** @return der Entity-Typ zu "minecraft:&lt;name&gt;" oder null, falls er nicht existiert. */
    static EntityType<?> get(String name) {
        return BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:" + name));
    }
}
