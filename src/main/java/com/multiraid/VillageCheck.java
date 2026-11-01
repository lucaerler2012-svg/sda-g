package com.multiraid;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;

/** Kleine Hilfsklasse zum Zaehlen von Dorfbewohnern (ohne Abhaengigkeit von der Villager-Klasse). */
final class VillageCheck {
    private VillageCheck() {
    }

    static int countVillagers(ServerLevel level, BlockPos center, double horizontalRadius, double verticalRadius) {
        AABB box = new AABB(
                center.getX() - horizontalRadius, center.getY() - verticalRadius, center.getZ() - horizontalRadius,
                center.getX() + horizontalRadius, center.getY() + verticalRadius, center.getZ() + horizontalRadius);
        EntityType<?> villager = RaidMobs.get("villager");
        if (villager == null) {
            return 0;
        }
        List<Entity> found = level.getEntities((Entity) null, box,
                entity -> entity.getType() == villager && entity.isAlive());
        return found.size();
    }
}
