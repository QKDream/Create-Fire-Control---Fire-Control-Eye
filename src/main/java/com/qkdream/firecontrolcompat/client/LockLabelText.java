package com.qkdream.firecontrolcompat.client;

import com.hooya.stabilizedturret.client.ClientRadarState;
import com.qkdream.firecontrolcompat.TargetClassifier;
import com.qkdream.firecontrolcompat.network.ContactTypePayload;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Shared client-side helpers that turn a radar contact id into the Chinese
 * category text drawn under lock boxes: {@code sable结构} for Sable
 * sublevels, {@code 弹药} for ordnance and {@code 生物} for living entities.
 */
public final class LockLabelText {

    public static final int LOCK_GREEN = -301924574;

    private LockLabelText() {
    }

    public static String classify(UUID id) {
        if (id == null) {
            return null;
        }
        Byte serverType = ContactTypePayload.lookup(id);
        if (serverType != null) {
            return labelFor(serverType);
        }
        Minecraft minecraft = Minecraft.getInstance();
        Entity entity = findEntity(minecraft, id);
        if (entity != null) {
            if (TargetClassifier.isCompatMissile(entity)) {
                return "弹药";
            }
            if (entity instanceof LivingEntity) {
                return "生物";
            }
            return "弹药";
        }
        return "sable结构";
    }

    /** Category text plus the friend-or-foe marking, e.g. {@code 弹药 · 友}. */
    public static Component component(UUID id) {
        String type = classify(id);
        return type == null ? null : Component.literal(type + iffMark(id));
    }

    /**
     * Friend-or-foe suffix: {@code · 友} when the contact carries a transponder
     * on the same band as the observer, {@code · 敌} on a different band and
     * {@code · 未知} when the structure carries no transponder at all.
     * Contact linked to no transponder at all stays unlabelled.
     */
    private static String iffMark(UUID id) {
        return switch (ContactTypePayload.lookupIff(id)) {
            case ContactTypePayload.IFF_FRIENDLY -> " · 友";
            case ContactTypePayload.IFF_ENEMY -> " · 敌";
            case ContactTypePayload.IFF_UNKNOWN -> " · 未知";
            default -> "";
        };
    }

    /**
     * Ground radar lock label: target category plus its speed relative to the
     * firing platform, e.g. {@code 弹药 · 12.3m/s}.
     */
    public static Component groundLockComponent(UUID id) {
        Component type = component(id);
        if (type == null) {
            return null;
        }
        Vec3 relative = relativeVelocity(id);
        if (relative == null) {
            return type;
        }
        String speed = String.format(Locale.ROOT, "%.1f", relative.length());
        return Component.literal(type.getString() + " · " + speed + "m/s");
    }

    private static Vec3 relativeVelocity(UUID id) {
        List<ClientRadarState.Contact> contacts = ClientRadarState.getContacts();
        if (contacts == null) {
            return null;
        }
        for (ClientRadarState.Contact contact : contacts) {
            if (!contact.id().equals(id)) {
                continue;
            }
            Vec3 velocity = contact.velocity();
            if (velocity == null) {
                return null;
            }
            Vec3 own = ClientRadarState.getOwnVelocity();
            return own == null ? velocity : velocity.subtract(own);
        }
        return null;
    }

    private static String labelFor(byte type) {
        if (type == ContactTypePayload.AMMO || type == ContactTypePayload.OTHER) {
            return "弹药";
        }
        if (type == ContactTypePayload.LIVING) {
            return "生物";
        }
        return "sable结构";
    }

    private static Entity findEntity(Minecraft minecraft, UUID id) {
        if (minecraft.level == null) {
            return null;
        }
        for (Entity candidate : minecraft.level.entitiesForRendering()) {
            if (candidate.getUUID().equals(id)) {
                return candidate;
            }
        }
        return null;
    }
}
