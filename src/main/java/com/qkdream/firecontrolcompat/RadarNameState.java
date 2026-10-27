package com.qkdream.firecontrolcompat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client side mirror of the radar names: the call sign of the computer whose
 * screen is open, and the call sign behind every RWR threat of the current
 * radar session.
 */
public final class RadarNameState {

    private static volatile String screenName = "";
    private static final Map<UUID, String> THREAT_NAMES = new ConcurrentHashMap<>();

    private RadarNameState() {
    }

    /** Name of the computer whose fire control screen is open. */
    public static String screenName() {
        return screenName;
    }

    public static void applyScreenName(String name) {
        screenName = name == null ? "" : name;
    }

    /** Replaces the per threat call signs carried by the last radar session. */
    public static void applyThreatNames(Map<UUID, String> names) {
        THREAT_NAMES.clear();
        if (names != null) {
            THREAT_NAMES.putAll(names);
        }
    }

    /** Call sign of an RWR threat, empty when the emitter carries no name. */
    public static String threatName(UUID id) {
        return id == null ? "" : THREAT_NAMES.getOrDefault(id, "");
    }
}
