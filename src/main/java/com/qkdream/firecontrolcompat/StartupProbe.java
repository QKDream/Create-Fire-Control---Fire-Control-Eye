package com.qkdream.firecontrolcompat;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Startup cost probe: how many milliseconds this mod's own start-up phases take.
 *
 * <p>The question this answers is "the pack loads slower - is it us?". Every phase this mod
 * runs on its own during start-up is wrapped here, each phase prints one
 * {@code startup-cost: <name>=<ms>} line, and {@link #finish()} prints a single summary line
 * and then closes the probe so nothing is logged once the game is running.</p>
 *
 * <p>Timing is always a {@link System#nanoTime()} difference: the clock never jumps, unlike
 * {@code currentTimeMillis}, so a phase cannot come out negative.</p>
 *
 * <p>Buckets, and what they mean (they are <em>not</em> additive - some nest):</p>
 * <ul>
 *   <li>{@code total} - wall clock from this class being first touched (the mod class is
 *       initialised) to the end of client set-up. This <strong>includes everything the other
 *       mods and the resource reload did in between</strong>; it is the window, not our cost.</li>
 *   <li>{@code constructor} - our {@code @Mod} constructor, which is where every registry
 *       object of this mod is created.</li>
 *   <li>{@code registry} - the {@code BeamMissileRegistry.register(...)} call (three deferred
 *       registers plus its two listeners), a subset of {@code constructor}.</li>
 *   <li>{@code setup} - the {@code FMLCommonSetupEvent} handler body.</li>
 *   <li>{@code renderers} / {@code item-icons} - the entity renderer registration and the
 *       GeckoLib item icon registration; together they are what {@code client} reports.</li>
 *   <li>{@code client-setup} - the {@code FMLClientSetupEvent} hook that ends the probe.</li>
 *   <li>{@code statics} - the sum of the static initialisers of the five utility classes
 *       ({@code MathIntercept}, {@code MathGuidedMissiles}, {@code IffRadar},
 *       {@code CompatScanner}, {@code SubLevelPositions}) that are <em>actually initialised
 *       during start-up</em>. These classes are lazy, so a class the game only touches in
 *       flight reports {@code static[Name]=n/a} - it then costs nothing at start-up.</li>
 *   <li>{@code cinfo} - what loading (not initialising) those same five classes costs, i.e.
 *       the JVM side of class info: parse, define, resolve. This is the honest number for a
 *       lazy class, and it is why {@code statics} can legitimately be 0.</li>
 *   <li>{@code ours} - {@code constructor + setup + client + statics + cinfo}: the part of
 *       {@code total} that this probe can attribute to this mod.</li>
 * </ul>
 */
public final class StartupProbe {

    private static final Logger LOG = LoggerFactory.getLogger("firecontrolcompat");

    /** Wall clock reference. Taken when this class is initialised, i.e. from our mod class. */
    private static final long START = System.nanoTime();

    /**
     * The utility classes the probe follows. They are loaded by name on purpose:
     * passing {@code MathIntercept.class} would make the caller load them, which is exactly
     * the cost this is trying to measure.
     */
    private static final String[] PROBE_CLASSES = {
            "com.qkdream.firecontrolcompat.missile.MathIntercept",
            "com.qkdream.firecontrolcompat.missile.MathGuidedMissiles",
            "com.qkdream.firecontrolcompat.IffRadar",
            "com.qkdream.firecontrolcompat.CompatScanner",
            "com.qkdream.firecontrolcompat.SubLevelPositions",
    };

    private static final Map<String, Double> STAGES = new LinkedHashMap<>();
    private static final Map<String, Long> STATIC_BEGAN = new LinkedHashMap<>();
    private static final Map<String, Double> STATIC_COST = new LinkedHashMap<>();
    private static final Map<String, Double> LOAD_COST = new LinkedHashMap<>();

    /** Start-up only: once the summary is out, every probe call is a no-op. */
    private static boolean open = true;
    private static boolean reported;

    private StartupProbe() {
    }

    /**
     * Starts the clock. Called from a static initialiser of the {@code @Mod} class, so
     * {@link #START} is taken as early as this mod can possibly observe.
     */
    public static void begin() {
        // Touching this class is the whole point; the clock is the static field above.
        synchronized (StartupProbe.class) {
            if (open) {
                LOG.info("startup-cost: probe armed (nanoTime reference taken)");
            }
        }
    }

    /** A {@link System#nanoTime()} reading to hand back to {@link #stage(String, long)}. */
    public static long tick() {
        return System.nanoTime();
    }

    /** Records and logs one phase that just ended. */
    public static void stage(String name, long startNanos) {
        long now = System.nanoTime();
        synchronized (StartupProbe.class) {
            if (!open) {
                return;
            }
            double millis = Math.max(0.0, (now - startNanos) / 1_000_000.0);
            STAGES.put(name, millis);
            LOG.info("startup-cost: {}={}", name, format(millis));
        }
    }

    /** Marks the first line of one of the probe classes' static initialisers. */
    public static void staticBegin(String name) {
        long now = System.nanoTime();
        synchronized (StartupProbe.class) {
            STATIC_BEGAN.put(name, now);
        }
    }

    /** Marks the last line of one of the probe classes' static initialisers. */
    public static void staticEnd(String name) {
        long now = System.nanoTime();
        synchronized (StartupProbe.class) {
            Long began = STATIC_BEGAN.remove(name);
            if (began == null) {
                return;
            }
            double millis = Math.max(0.0, (now - began) / 1_000_000.0);
            STATIC_COST.put(name, millis);
            if (open) {
                LOG.info("startup-cost: static[{}]={}", name, format(millis));
            }
        }
    }

    /**
     * Loads (does not initialise) the probe classes and records what that costs.
     *
     * <p>{@code Class.forName(..., false, ...)} is deliberate: initialising a lazy class early
     * would run its static initialiser at a moment the game does not expect, and a failure
     * there poisons the class for the whole session. Loading is safe and is the only part of a
     * lazy class that start-up ever pays for.</p>
     */
    public static void classInfo() {
        long start = System.nanoTime();
        // The loader that defined this class is by definition the one that defines the rest of
        // the mod, so it is the only one worth asking.
        ClassLoader loader = StartupProbe.class.getClassLoader();
        synchronized (StartupProbe.class) {
            if (!open) {
                return;
            }
            for (String name : PROBE_CLASSES) {
                long began = System.nanoTime();
                boolean loaded = true;
                try {
                    Class.forName(name, false, loader);
                } catch (Throwable failure) {
                    loaded = false;
                }
                double millis = Math.max(0.0, (System.nanoTime() - began) / 1_000_000.0);
                String simple = simpleName(name);
                if (loaded) {
                    LOAD_COST.put(simple, millis);
                }
                LOG.info("startup-cost: load[{}]={}{}", simple, format(millis),
                        loaded ? "" : " (not loadable)");
            }
            double millis = Math.max(0.0, (System.nanoTime() - start) / 1_000_000.0);
            STAGES.put("cinfo", millis);
            LOG.info("startup-cost: cinfo={}", format(millis));
        }
    }

    /**
     * Prints the one summary line and closes the probe. Safe to call more than once - only the
     * first call reports, and every later probe call logs nothing.
     */
    public static void finish() {
        synchronized (StartupProbe.class) {
            if (!open || reported) {
                return;
            }
            double total = Math.max(0.0, (System.nanoTime() - START) / 1_000_000.0);
            double constructor = value("constructor");
            double registry = value("registry");
            double setup = value("setup");
            double renderers = value("renderers");
            double icons = value("item-icons");
            double clientSetup = value("client-setup");
            double client = renderers + icons + clientSetup;
            double statics = 0.0;
            for (double millis : STATIC_COST.values()) {
                statics += millis;
            }
            double cinfo = value("cinfo");
            double ours = constructor + setup + client + statics + cinfo;
            // Classes the game never initialised during start-up cost nothing there; say so
            // rather than leaving the reader to wonder about a silent zero.
            StringBuilder missing = new StringBuilder();
            for (String name : PROBE_CLASSES) {
                String simple = simpleName(name);
                if (!STATIC_COST.containsKey(simple)) {
                    LOG.info("startup-cost: static[{}]=n/a (not initialised during startup)", simple);
                    if (missing.length() > 0) {
                        missing.append(',');
                    }
                    missing.append(simple);
                }
            }
            LOG.info("startup-cost: total={} registry={} setup={} client={} statics={} cinfo={} ours={}",
                    format(total), format(registry), format(setup), format(client),
                    format(statics), format(cinfo), format(ours));
            LOG.info("startup-cost: buckets ms constructor={} registry={} setup={} renderers={} item-icons={} "
                            + "client-setup={} statics={} statics-count={}/{} cinfo={}",
                    format(constructor), format(registry), format(setup), format(renderers),
                    format(icons), format(clientSetup), format(statics),
                    String.valueOf(STATIC_COST.size()), String.valueOf(PROBE_CLASSES.length),
                    format(cinfo));
            if (missing.length() > 0) {
                LOG.info("startup-cost: lazy (zero startup init) = {}", missing);
            }
            open = false;
            reported = true;
        }
    }

    /** True while the probe still logs; a read-only view for callers that want to skip work. */
    public static boolean isOpen() {
        synchronized (StartupProbe.class) {
            return open;
        }
    }

    private static double value(String name) {
        Double millis = STAGES.get(name);
        return millis == null ? 0.0 : millis;
    }

    private static String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    private static String format(double millis) {
        return String.format(Locale.ROOT, "%.1f", millis);
    }
}
