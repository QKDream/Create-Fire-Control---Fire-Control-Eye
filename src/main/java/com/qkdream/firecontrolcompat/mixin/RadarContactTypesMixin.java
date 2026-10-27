package com.qkdream.firecontrolcompat.mixin;

import com.hooya.stabilizedturret.network.RadarSessionPayload;
import com.hooya.stabilizedturret.network.ServerAimSessionHandler;
import com.qkdream.firecontrolcompat.FireControlCompat;
import com.qkdream.firecontrolcompat.IffRadar;
import com.qkdream.firecontrolcompat.RadarEmitters;
import com.qkdream.firecontrolcompat.RadarNameStore;
import com.qkdream.firecontrolcompat.ShaolibBridge;
import com.qkdream.firecontrolcompat.TargetClassifier;
import com.qkdream.firecontrolcompat.network.ContactTypePayload;
import com.qkdream.firecontrolcompat.network.RadarThreatLabelPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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

    /** Throttle of the RWR label log line, one line per second and player. */
    @Unique
    private static long firecontrolcompat$lastLabelLogTick = Long.MIN_VALUE;

    @Unique
    private static UUID firecontrolcompat$lastLabelLogPlayer;

    /** Radar threats printed per log line. */
    @Unique
    private static final int COMPAT_LABEL_LOG_LIMIT = 8;

    @Redirect(
            method = "onPlayerTick",
            at = @At(
                    value = "INVOKE",
                    // The radar session send is the first payload of the tick; the
                    // later sends in the same method carry other sessions.
                    ordinal = 0,
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
            PacketDistributor.sendToPlayer(
                    player,
                    firecontrolcompat$threatNames(player, player.serverLevel(), session),
                    new CustomPacketPayload[0]);
        }
    }

    private static ContactTypePayload firecontrolcompat$types(
            ServerPlayer player, ServerLevel level, RadarSessionPayload session) {
        Set<String> own = IffRadar.ownChannelsOf(player);
        List<ContactTypePayload.Entry> entries = new ArrayList<>(session.contacts().size());
        for (RadarSessionPayload.Contact contact : session.contacts()) {
            UUID id = contact.id();
            entries.add(new ContactTypePayload.Entry(
                    id,
                    firecontrolcompat$classify(level, id),
                    IffRadar.mark(level, id, own)));
        }
        return new ContactTypePayload(entries);
    }

    /**
     * Call signs of the radars painting the player, matched to the threats of
     * this session: the name set on a fire control screen replaces the plain
     * threat type on the opposing radar warning receiver. Missiles are left
     * out on purpose, they always keep their plain MSL mark.
     *
     * <p>Every radar mark of the session is also written to the log once per
     * second together with the route that did or did not find a call sign, so a
     * label that still comes out as a bare type can be traced from the log
     * instead of from a guess.</p>
     */
    private static RadarThreatLabelPayload firecontrolcompat$threatNames(
            ServerPlayer player, ServerLevel level, RadarSessionPayload session) {
        boolean log = firecontrolcompat$shouldLogLabels(level, player, session);
        List<RadarThreatLabelPayload.Entry> entries = new ArrayList<>(session.threats().size());
        StringBuilder report = log ? new StringBuilder() : null;
        int reported = 0;
        for (RadarSessionPayload.Threat threat : session.threats()) {
            if (threat.type() == RadarSessionPayload.ThreatType.MSL) {
                continue;
            }
            RadarNameStore.Resolution resolution = RadarNameStore.resolve(
                    level, threat.id(), new Vec3(threat.x(), threat.y(), threat.z()));
            entries.add(new RadarThreatLabelPayload.Entry(threat.id(), resolution.name()));
            if (report != null && reported < COMPAT_LABEL_LOG_LIMIT) {
                if (reported > 0) {
                    report.append(' ');
                }
                report.append('[')
                        .append(threat.type())
                        .append(" id=")
                        .append(threat.id().toString(), 0, 4)
                        .append(" pos=")
                        .append(String.format("%.1f,%.1f,%.1f", threat.x(), threat.y(), threat.z()))
                        .append(" name='")
                        .append(resolution.name())
                        .append("' route=")
                        .append(resolution.route())
                        .append(']');
                reported++;
            }
        }
        if (report != null) {
            FireControlCompat.LOGGER.info(
                    "RWR labels for {}: threats={} {}",
                    player.getName().getString(),
                    session.threats().size(),
                    report);
        }
        return new RadarThreatLabelPayload(entries);
    }

    /** One report per second and player, and only while the receiver hears a radar. */
    private static boolean firecontrolcompat$shouldLogLabels(
            ServerLevel level, ServerPlayer player, RadarSessionPayload session) {
        boolean radar = session.threats().stream()
                .anyMatch(threat -> threat.type() != RadarSessionPayload.ThreatType.MSL);
        if (!radar) {
            return false;
        }
        long now = level.getGameTime();
        UUID playerId = player.getUUID();
        if (now - firecontrolcompat$lastLabelLogTick < 20L && playerId.equals(firecontrolcompat$lastLabelLogPlayer)) {
            return false;
        }
        firecontrolcompat$lastLabelLogTick = now;
        firecontrolcompat$lastLabelLogPlayer = playerId;
        return true;
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

}