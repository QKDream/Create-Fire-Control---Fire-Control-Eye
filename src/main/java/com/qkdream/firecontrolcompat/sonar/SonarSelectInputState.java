package com.qkdream.firecontrolcompat.sonar;

/**
 * The bindings the computer on screen answers to for the sonar pick, as the
 * server last reported them.
 *
 * <p>What is held here is only what the page draws - the names of the two
 * frequency items and the two remote signals - because the pick itself is read
 * off the computer's own file on the server. The page writes a binding through
 * the computer and the server answers with the file, so a binding that was
 * turned down never shows up here.</p>
 */
public final class SonarSelectInputState {

    private static String first = "";
    private static String second = "";
    private static String normalKey = "";
    private static String advancedKey = "";
    private static String wirelessKey = "";

    private SonarSelectInputState() {
    }

    /** Takes the bindings the server reported for the computer on screen. */
    public static void accept(String first, String second, String normalKey, String advancedKey,
            String wirelessKey) {
        SonarSelectInputState.first = clean(first);
        SonarSelectInputState.second = clean(second);
        SonarSelectInputState.normalKey = clean(normalKey);
        SonarSelectInputState.advancedKey = clean(advancedKey);
        SonarSelectInputState.wirelessKey = clean(wirelessKey);
    }

    /**
     * The key the full-keyboard wireless controller answers to, empty when it
     * answers to none. This is 火控学's third channel: the one whose binds are a
     * whole keyboard plus the mouse wheel.
     */
    public static String wirelessKey() {
        return wirelessKey;
    }

    /** Name of the item filed as the first half of the wireless pair, or empty. */
    public static String first() {
        return first;
    }

    /** Name of the item filed as the second half of the wireless pair, or empty. */
    public static String second() {
        return second;
    }

    /**
     * The signal the ordinary remote controller answers to, empty when it
     * answers to none.
     */
    public static String normalKey() {
        return normalKey;
    }

    /** The signal the advanced remote controller answers to, empty when none. */
    public static String advancedKey() {
        return advancedKey;
    }

    private static String clean(String value) {
        return value == null ? "" : value;
    }
}
