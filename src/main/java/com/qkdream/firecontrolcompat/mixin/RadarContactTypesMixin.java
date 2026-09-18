package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.network.RadarSessionPayload;
import com.hooya.stabilizedturret.network.ServerAimSessionHandler;
import com.qkdream.firecontrolcompat.ShaolibBridge;
import com.qkdream.firecontrolcompat.TargetClassifier;
import com.qkdream.firecontrolcompat.iff.IffBand;
import com.qkdream.firecontrolcompat.iff.IffLinking;
import com.qkdream.firecontrolcompat.iff.IffOwnership;
import com.qkdream.firecontrolcompat.iff.IffTransponderBlockEntity;
import com.qkdream.firecontrolcompat.network.ContactTypePayload;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Piggybacks the server-side contact categories onto the radar session: right
 * after the vanilla {@code RadarSessionPayload} is sent, a compact
 * {@code ContactTypePayload} with the category and friend-or-foe marking of
 * every contact is sent too. The server can always tell Sable sublevels from
 * entities, no matter how far away the entity is from the client.
 */
@Mixin(ServerAimSessionHandler.class)
public abstract class RadarContactTypesMixin {

    @Redirect(
            method = "onPlayerTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/network/PacketDistributor;sendToPlayer(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;[Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V"
            )
    )
    private static void firecontrolcompat$sendToPlayerWithTypes(
            ServerPlayer player,
            CustomPacketPayload payload,
            CustomPacketPayload[] extra
    ) {
        PacketDistributor.sendToPlayer(player, payload, extra);
        if (payload instanceof RadarSessionPayload session) {
            PacketDistributor.sendToPlayer(
                    player, firecontrolcompat$types(player, player.serverLevel(), session), new CustomPacketPayload[0]);
        }
    }

    private static ContactTypePayload firecontrolcompat$types(
            ServerPlayer player, ServerLevel level, RadarSessionPayload session) {
        IffBand own = IffLinking.ownBand(player);
        long gameTime = level.getGameTime();
        List<ContactTypePayload.Entry> entries = new ArrayList<>(session.contacts().size());
        for (RadarSessionPayload.Contact contact : session.contacts()) {
            UUID id = contact.id();
            entries.add(new ContactTypePayload.Entry(
                    id,
                    firecontrolcompat$classify(level, id),
                    firecontrolcompat$iff(level, gameTime, id, own)));
        }
        return new ContactTypePayload(entries);
    }

    private static byte firecontrolcompat$classify(ServerLevel level, UUID id) {
        if (ShaolibBridge.isShaolibId(id)) {
            return ContactTypePayload.AMMO;
        }
        Entity entity = level.getEntity(id);
        if (entity != null) {
            if (TargetClassifier.isCompatMissile(entity)) {
                return ContactTypePayload.AMMO;
            }
            if (entity instanceof LivingEntity) {
                return ContactTypePayload.LIVING;
            }
            return ContactTypePayload.OTHER;
        }
        return ContactTypePayload.SABLE;
    }

    /**
     * Friend-or-foe marking. Structures carrying a transponder are friendly
     * when their band matches the band of the transponder wired to the
     * observer's fire control computer, hostile when it differs and unknown
     * when they carry no transponder at all. Ordnance inherits the marking of
     * the structure that released it, and unmarked ordnance stays unlabelled.
     */
    private static byte firecontrolcompat$iff(
            ServerLevel level, long gameTime, UUID id, IffBand own) {
        if (own == null || ShaolibBridge.isShaolibId(id)) {
            return ContactTypePayload.IFF_NONE;
        }
        Entity entity = level.getEntity(id);
        boolean structure = entity == null;
        UUID owner;
        if (structure) {
            owner = id;
        } else {
            if (!TargetClassifier.isCompatMissile(entity)) {
                return ContactTypePayload.IFF_NONE;
            }
            owner = IffOwnership.subLevelOf(level, entity);
        }
        if (owner == null) {
            return ContactTypePayload.IFF_NONE;
        }
        IffTransponderBlockEntity.Presence presence =
                IffTransponderBlockEntity.presenceInSubLevel(gameTime, level, owner);
        if (!presence.present()) {
            return structure ? ContactTypePayload.IFF_UNKNOWN : ContactTypePayload.IFF_NONE;
        }
        if (presence.bands().isEmpty()) {
            return ContactTypePayload.IFF_UNKNOWN;
        }
        for (IffBand band : presence.bands()) {
            if (band.sameAs(own)) {
                return ContactTypePayload.IFF_FRIENDLY;
            }
        }
        return ContactTypePayload.IFF_ENEMY;
    }
}