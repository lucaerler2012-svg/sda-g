package com.multiraid;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Ein einzelner, vollstaendig unabhaengiger Raid mit eigener Bossbar, eigenen Wellen und eigenen Gegnern.
 */
final class ActiveRaid {
    private enum Phase { PREPARING, WAVE, BREAK, ENDING }

    private enum Result { NONE, VICTORY, DEFEAT, ABORTED }

    /** Ein gefundener, begehbarer Spawnplatz (y = Oberkante des Bodens). */
    private record Spot(double x, double y, double z) { }

    static final int TOTAL_WAVES = 5;
    private static final int PREPARE_TICKS = 300;
    private static final int BREAK_TICKS = 200;
    private static final int END_DISPLAY_TICKS = 100;
    private static final int WAVE_TIMEOUT_TICKS = 12000;
    private static final int NO_PLAYER_TIMEOUT_TICKS = 1200;
    private static final double PLAYER_RANGE = 128.0;
    private static final double VILLAGE_RADIUS = 64.0;
    private static final double VILLAGE_HEIGHT = 48.0;

    private final int id;
    private final ResourceKey<Level> dimension;
    private final BlockPos center;
    private final int omenLevel;
    private final Random random = new Random();
    private final Set<UUID> raiders = new HashSet<>();
    private final ServerBossEvent bar;

    private Phase phase = Phase.PREPARING;
    private Result result = Result.NONE;
    private int currentWave = 0;
    private int waveStartCount = 0;
    private int phaseTicks = 0;
    private int age = 0;
    private int noPlayerTicks = 0;
    private int spawnFailures = 0;
    private final List<Spot> spots = new ArrayList<>();
    private boolean closed = false;

    ActiveRaid(int id, ResourceKey<Level> dimension, BlockPos center, int omenLevel) {
        this.id = id;
        this.dimension = dimension;
        this.center = center.immutable();
        this.omenLevel = omenLevel;
        this.bar = new ServerBossEvent(
                UUID.randomUUID(),
                Component.literal(prepareTitle()),
                BossEvent.BossBarColor.PURPLE,
                BossEvent.BossBarOverlay.PROGRESS);
        this.bar.setProgress(0.0F);
    }

    int getId() {
        return id;
    }

    void announceStart(MinecraftServer server, ServerLevel level, ServerPlayer starter) {
        refreshPlayers(server, level);
        sendToPlayers(server, level, "Raid " + id + " beginnt! Gestartet von "
                + starter.getName().getString() + ". Die erste Welle kommt gleich.");
    }

    /** @return true, wenn der Raid beendet und aufgeraeumt ist und aus der Liste entfernt werden kann. */
    boolean tick(MinecraftServer server) {
        if (closed) {
            return true;
        }
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            forceClose(server);
            return true;
        }

        age++;
        phaseTicks++;
        boolean second = age % 20 == 0;

        if (second) {
            refreshPlayers(server, level);
        }

        switch (phase) {
            case PREPARING -> {
                bar.setProgress(clamp((float) phaseTicks / PREPARE_TICKS));
                if (second && checkAbortOrLoss(server, level)) {
                    return false;
                }
                if (phaseTicks >= PREPARE_TICKS) {
                    startWave(server, level);
                }
            }
            case WAVE -> {
                if (second) {
                    if (checkAbortOrLoss(server, level)) {
                        return false;
                    }
                    int alive = countAliveRaiders(level);
                    float progress = waveStartCount <= 0 ? 0.0F : (float) alive / waveStartCount;
                    bar.setProgress(clamp(progress));
                    if (alive == 0 || phaseTicks >= WAVE_TIMEOUT_TICKS) {
                        raiders.clear();
                        onWaveCleared(server, level);
                    }
                }
            }
            case BREAK -> {
                bar.setProgress(clamp((float) phaseTicks / BREAK_TICKS));
                if (second && checkAbortOrLoss(server, level)) {
                    return false;
                }
                if (phaseTicks >= BREAK_TICKS) {
                    startWave(server, level);
                }
            }
            case ENDING -> {
                if (phaseTicks >= END_DISPLAY_TICKS) {
                    close();
                    return true;
                }
            }
        }
        return closed;
    }

    // ------------------------------------------------------------------ Wellen

    private void startWave(MinecraftServer server, ServerLevel level) {
        currentWave++;
        phase = Phase.WAVE;
        phaseTicks = 0;
        raiders.clear();
        scanSpawnSpots(level);

        spawnGroup(level, "pillager", 3 + currentWave + omenLevel);
        if (currentWave >= 2) {
            spawnGroup(level, "vindicator", currentWave - 1 + omenLevel / 2);
        }
        if (currentWave >= 3) {
            spawnGroup(level, "witch", 1);
        }
        if (currentWave >= 4) {
            spawnGroup(level, "evoker", currentWave == 5 ? 2 : 1);
        }
        if (currentWave == 3 || currentWave == 5) {
            spawnGroup(level, "ravager", currentWave == 5 && omenLevel >= 3 ? 2 : 1);
        }

        waveStartCount = raiders.size();
        spots.clear();
        if (waveStartCount == 0) {
            // Kein fester Boden gefunden: Welle nicht zaehlen und spaeter erneut versuchen.
            currentWave--;
            spawnFailures++;
            if (spawnFailures >= 6) {
                sendToPlayers(server, level, "Raid " + id + " abgebrochen: Kein fester Boden zum Spawnen gefunden.");
                finish(server, level, Result.ABORTED);
                return;
            }
            phase = Phase.BREAK;
            phaseTicks = BREAK_TICKS - 100;
            bar.setColor(BossEvent.BossBarColor.YELLOW);
            bar.setName(Component.literal("Raid " + id + " \u2013 Suche festen Boden"));
            bar.setProgress(0.0F);
            return;
        }
        spawnFailures = 0;
        bar.setColor(BossEvent.BossBarColor.RED);
        bar.setName(Component.literal(waveTitle()));
        bar.setProgress(waveStartCount > 0 ? 1.0F : 0.0F);
        sendToPlayers(server, level, "Raid " + id + ": Welle " + currentWave + "/" + TOTAL_WAVES + " beginnt!");
    }

    private void onWaveCleared(MinecraftServer server, ServerLevel level) {
        if (currentWave >= TOTAL_WAVES) {
            finish(server, level, Result.VICTORY);
            return;
        }
        phase = Phase.BREAK;
        phaseTicks = 0;
        bar.setColor(BossEvent.BossBarColor.YELLOW);
        bar.setName(Component.literal("Raid " + id + " – Welle " + currentWave + "/" + TOTAL_WAVES + " geschafft"));
        bar.setProgress(0.0F);
        sendToPlayers(server, level, "Raid " + id + ": Welle " + currentWave + "/" + TOTAL_WAVES
                + " besiegt. Die naechste Welle naht.");
    }

    private void spawnGroup(ServerLevel level, String typeId, int count) {
        for (int i = 0; i < count; i++) {
            Entity entity = spawnOne(level, typeId);
            if (entity != null) {
                raiders.add(entity.getUUID());
            }
        }
    }

    private Entity spawnOne(ServerLevel level, String typeId) {
        EntityType<?> type = RaidMobs.get(typeId);
        if (type == null || spots.isEmpty()) {
            return null;
        }
        Spot spot = spots.get(random.nextInt(spots.size()));
        Entity entity = type.create(level, EntitySpawnReason.EVENT);
        if (entity == null) {
            return null;
        }
        entity.setPos(spot.x(), spot.y(), spot.z());
        if (entity instanceof Mob mob) {
            mob.setPersistenceRequired();
            if ("pillager".equals(typeId)) {
                mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));
            } else if ("vindicator".equals(typeId)) {
                mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
            }
        }
        entity.setGlowingTag(true);
        if (level.addFreshEntity(entity)) {
            return entity;
        }
        return null;
    }

    /**
     * Prueft systematisch jede Spalte im Umkreis (6 bis 40 Bloecke) und merkt sich alle Plaetze, auf denen
     * ein Gegner stehen kann: Der Boden hat eine Kollisionsform mit Oberkante 0,5 bis 1,0 (volle Bloecke,
     * Halbplatten), enthaelt keine Fluessigkeit, und Fuss- sowie Kopfblock darueber sind frei und trocken.
     * Plaetze ab 14 Bloecken Abstand werden bevorzugt, sonst werden naehere genommen.
     */
    private void scanSpawnSpots(ServerLevel level) {
        spots.clear();
        List<Spot> near = new ArrayList<>();
        int radius = 40;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int distSq = dx * dx + dz * dz;
                if (distSq > radius * radius || distSq < 36) {
                    continue;
                }
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                if (!level.hasChunkAt(new BlockPos(x, center.getY(), z))) {
                    continue;
                }
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                if (Math.abs(top - center.getY()) > 32) {
                    continue;
                }

                BlockPos ground = new BlockPos(x, top - 1, z);
                BlockPos feet = ground.above();
                BlockPos head = feet.above();
                BlockState groundState = level.getBlockState(ground);
                VoxelShape shape = groundState.getCollisionShape(level, ground);
                if (shape.isEmpty()) {
                    continue;
                }
                double height = shape.max(Direction.Axis.Y);
                if (height < 0.5 || height > 1.0) {
                    continue;
                }
                if (!level.getFluidState(ground).isEmpty()
                        || !level.getFluidState(feet).isEmpty()
                        || !level.getFluidState(head).isEmpty()) {
                    continue;
                }
                if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                        || !level.getBlockState(head).getCollisionShape(level, head).isEmpty()) {
                    continue;
                }

                Spot spot = new Spot(x + 0.5, ground.getY() + height, z + 0.5);
                if (distSq >= 196) {
                    spots.add(spot);
                } else {
                    near.add(spot);
                }
            }
        }
        if (spots.isEmpty()) {
            spots.addAll(near);
        }
    }

    private int countAliveRaiders(ServerLevel level) {
        int alive = 0;
        List<UUID> gone = new ArrayList<>();
        for (UUID uuid : raiders) {
            Entity entity = level.getEntity(uuid);
            if (entity != null && entity.isAlive()) {
                alive++;
            } else {
                gone.add(uuid);
            }
        }
        gone.forEach(raiders::remove);
        return alive;
    }

    // ------------------------------------------------------------------ Ende

    /** Prueft Dorf-Zerstoerung (Niederlage) und fehlende Spieler (Abbruch). */
    private boolean checkAbortOrLoss(MinecraftServer server, ServerLevel level) {
        if (VillageCheck.countVillagers(level, center, VILLAGE_RADIUS, VILLAGE_HEIGHT) == 0) {
            finish(server, level, Result.DEFEAT);
            return true;
        }
        if (playersInRange(server, level).isEmpty()) {
            noPlayerTicks += 20;
            if (noPlayerTicks >= NO_PLAYER_TIMEOUT_TICKS) {
                finish(server, level, Result.ABORTED);
                return true;
            }
        } else {
            noPlayerTicks = 0;
        }
        return false;
    }

    private void finish(MinecraftServer server, ServerLevel level, Result newResult) {
        phase = Phase.ENDING;
        phaseTicks = 0;
        result = newResult;
        List<ServerPlayer> players = playersInRange(server, level);

        switch (newResult) {
            case VICTORY -> {
                bar.setColor(BossEvent.BossBarColor.GREEN);
                bar.setName(Component.literal("Raid " + id + " – Sieg!"));
                bar.setProgress(1.0F);
                for (ServerPlayer player : players) {
                    player.addEffect(new MobEffectInstance(
                            MobEffects.HERO_OF_THE_VILLAGE, 48000, Math.max(0, omenLevel - 1)));
                    player.sendSystemMessage(Component.literal("Raid " + id + " gewonnen! Held des Dorfes!"));
                }
            }
            case DEFEAT -> {
                discardRaiders(level);
                bar.setColor(BossEvent.BossBarColor.RED);
                bar.setName(Component.literal("Raid " + id + " – Niederlage"));
                bar.setProgress(0.0F);
                for (ServerPlayer player : players) {
                    player.sendSystemMessage(Component.literal("Raid " + id + " verloren: Das Dorf wurde zerstoert."));
                }
            }
            default -> {
                discardRaiders(level);
                bar.setColor(BossEvent.BossBarColor.WHITE);
                bar.setName(Component.literal("Raid " + id + " – Abgebrochen"));
                bar.setProgress(0.0F);
            }
        }
    }

    /** Beendet den Raid sofort und raeumt Bossbar und Gegner auf (Serverstopp, Fehler, Weltwechsel). */
    void forceClose(MinecraftServer server) {
        if (closed) {
            return;
        }
        ServerLevel level = server.getLevel(dimension);
        if (level != null) {
            discardRaiders(level);
        }
        close();
    }

    private void discardRaiders(ServerLevel level) {
        for (UUID uuid : raiders) {
            Entity entity = level.getEntity(uuid);
            if (entity != null) {
                entity.discard();
            }
        }
        raiders.clear();
    }

    private void close() {
        closed = true;
        bar.setVisible(false);
        bar.removeAllPlayers();
    }

    // ------------------------------------------------------------------ Spieler & Bossbar

    private List<ServerPlayer> playersInRange(MinecraftServer server, ServerLevel level) {
        List<ServerPlayer> result = new ArrayList<>();
        double maxSq = PLAYER_RANGE * PLAYER_RANGE;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.isAlive() || !player.level().dimension().equals(dimension)) {
                continue;
            }
            if (player.distanceToSqr(center.getX() + 0.5, center.getY(), center.getZ() + 0.5) <= maxSq) {
                result.add(player);
            }
        }
        return result;
    }

    private void refreshPlayers(MinecraftServer server, ServerLevel level) {
        List<ServerPlayer> inRange = playersInRange(server, level);
        for (ServerPlayer current : new ArrayList<>(bar.getPlayers())) {
            if (!inRange.contains(current)) {
                bar.removePlayer(current);
            }
        }
        for (ServerPlayer player : inRange) {
            bar.addPlayer(player);
        }
    }

    private void sendToPlayers(MinecraftServer server, ServerLevel level, String text) {
        Component message = Component.literal(text);
        for (ServerPlayer player : playersInRange(server, level)) {
            player.sendSystemMessage(message);
        }
    }

    // ------------------------------------------------------------------ Titel

    private String prepareTitle() {
        return "Raid " + id + " – Vorbereitung";
    }

    private String waveTitle() {
        return "Raid " + id + " – Welle " + currentWave + "/" + TOTAL_WAVES;
    }

    private static float clamp(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }
}
