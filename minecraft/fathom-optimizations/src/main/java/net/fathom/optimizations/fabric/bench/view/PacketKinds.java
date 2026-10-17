package net.fathom.optimizations.fabric.bench.view;

import net.minecraft.network.packet.s2c.play.*;

import net.fathom.optimizations.core.bench.view.PacketKind;

/**
 * Which {@link PacketKind} a packet class belongs to, decided once per class.
 *
 * <p>By class literal rather than by name: in a production launch the classes
 * carry intermediary names, so a name match would find nothing.
 */
public final class PacketKinds {

    private PacketKinds() {}

    private static final ClassValue<PacketKind> KINDS = new ClassValue<PacketKind>() {
        @Override
        protected PacketKind computeValue(Class<?> type) {
            return classify(type);
        }
    };

    public static int of(Object packet) {
        return packet == null ? PacketKind.OTHER.ordinal() : KINDS.get(packet.getClass()).ordinal();
    }

    static PacketKind classify(Class<?> type) {
        if (is(type, EntityS2CPacket.class, EntityPositionS2CPacket.class, EntitySetHeadYawS2CPacket.class,
                EntityVelocityUpdateS2CPacket.class)) {
            return PacketKind.MOVE;
        }
        if (is(type, EntitySpawnS2CPacket.class, PlayerSpawnS2CPacket.class, MobSpawnS2CPacket.class,
                EntitiesDestroyS2CPacket.class, ExperienceOrbSpawnS2CPacket.class, PaintingSpawnS2CPacket.class,
                EntitySpawnGlobalS2CPacket.class)) {
            return PacketKind.SPAWN;
        }
        if (is(type, EntityTrackerUpdateS2CPacket.class, EntityAttributesS2CPacket.class,
                EntityStatusEffectS2CPacket.class, RemoveEntityStatusEffectS2CPacket.class,
                EntityAttachS2CPacket.class, UpdateEntityNbtS2CPacket.class)) {
            return PacketKind.METADATA;
        }
        if (is(type, EntityEquipmentUpdateS2CPacket.class)) {
            return PacketKind.EQUIPMENT;
        }
        if (is(type, EntityAnimationS2CPacket.class, EntityStatusS2CPacket.class)) {
            return PacketKind.ANIMATION;
        }
        if (is(type, ChunkDataS2CPacket.class, ChunkMapS2CPacket.class)) {
            return PacketKind.CHUNK;
        }
        if (is(type, BlockUpdateS2CPacket.class, ChunkDeltaUpdateS2CPacket.class, BlockEntityUpdateS2CPacket.class,
                BlockActionS2CPacket.class, BlockBreakingProgressS2CPacket.class, ExplosionS2CPacket.class,
                WorldEventS2CPacket.class, UpdateSignS2CPacket.class, MapUpdateS2CPacket.class)) {
            return PacketKind.BLOCKS;
        }
        if (is(type, ScoreboardObjectiveUpdateS2CPacket.class, ScoreboardPlayerUpdateS2CPacket.class,
                ScoreboardDisplayS2CPacket.class)) {
            return PacketKind.SCOREBOARD;
        }
        if (is(type, TeamS2CPacket.class)) {
            return PacketKind.TEAMS;
        }
        if (is(type, PlayerListS2CPacket.class, PlayerListHeaderS2CPacket.class)) {
            return PacketKind.PLAYER_LIST;
        }
        if (is(type, ParticleS2CPacket.class)) {
            return PacketKind.PARTICLES;
        }
        if (is(type, PlaySoundIdS2CPacket.class)) {
            return PacketKind.SOUND;
        }
        if (is(type, ChatMessageS2CPacket.class, TitleS2CPacket.class)) {
            return PacketKind.CHAT;
        }
        if (is(type, InventoryS2CPacket.class, ScreenHandlerSlotUpdateS2CPacket.class, HeldItemChangeS2CPacket.class,
                ConfirmGuiActionS2CPacket.class, OpenScreenS2CPacket.class, CloseScreenS2CPacket.class,
                ScreenHandlerPropertyUpdateS2CPacket.class)) {
            return PacketKind.INVENTORY;
        }
        if (is(type, PlayerPositionLookS2CPacket.class, HealthUpdateS2CPacket.class,
                ExperienceBarUpdateS2CPacket.class, PlayerAbilitiesS2CPacket.class, CombatEventS2CPacket.class,
                SetCameraEntityS2CPacket.class, PlayerRespawnS2CPacket.class, StatsUpdateS2CPacket.class)) {
            return PacketKind.SELF;
        }
        if (is(type, WorldTimeUpdateS2CPacket.class, GameStateChangeS2CPacket.class, WorldBorderS2CPacket.class,
                PlayerSpawnPositionS2CPacket.class, DifficultyS2CPacket.class, ChunkLoadDistanceS2CPacket.class)) {
            return PacketKind.WORLD;
        }
        return PacketKind.OTHER;
    }

    private static boolean is(Class<?> type, Class<?>... candidates) {
        for (Class<?> candidate : candidates) {
            if (candidate.isAssignableFrom(type)) {
                return true;
            }
        }
        return false;
    }
}
