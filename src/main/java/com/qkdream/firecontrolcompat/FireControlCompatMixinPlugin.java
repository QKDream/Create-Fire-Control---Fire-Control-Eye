package com.qkdream.firecontrolcompat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Applies the optional-mod mixins only while their mod is installed.
 *
 * <p>Mixin resolves every member reference of a handler while it is attached,
 * so a handler that calls into another mod (for example
 * {@code GunBlockEntity#setPlantFireActive}) aborts mod loading when that mod
 * is missing. Gating those mixins here keeps the game loading with TAOV
 * weapons, CBCMS or vestalihy absent.</p>
 */
public final class FireControlCompatMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("firecontrolcompat");

    /**
     * Set {@code mixins=off} in {@code config/firecontrolcompat-pod.txt} to load
     * this mod with none of its mixins applied at all.
     *
     * <p>It exists to place a fault that only shows with this mod present: with
     * the mixins gone the mod still loads and its own items stay, but every hook
     * it puts into fire control, 面包学 or a client renderer is missing, so one
     * restart says whether the fault lives in that layer at all.</p>
     */
    private static final boolean MIXINS_ENABLED = readMixinsEnabled();

    /**
     * Mixins switched off by name, from {@code mixinsOff=Name,Name} in the config.
     *
     * <p>Narrower than the master switch and steadier than quartering: when one
     * hook is suspected, it can be taken out on its own and put back in a restart,
     * without touching the other hundred.</p>
     */
    private static final java.util.Set<String> MIXINS_OFF = readMixinsOff();

    private static java.util.Set<String> readMixinsOff() {
        java.util.Set<String> off = new java.util.HashSet<>();
        try {
            java.nio.file.Path path = java.nio.file.Path.of("config/firecontrolcompat-pod.txt");
            if (!java.nio.file.Files.isRegularFile(path)) {
                return off;
            }
            for (String line : java.nio.file.Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8)) {
                String text = line.trim().replace(" ", "");
                if (!text.toLowerCase().startsWith("mixinsoff")) {
                    continue;
                }
                int equals = text.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                for (String part : text.substring(equals + 1).split(",")) {
                    String name = part.trim();
                    int dot = name.lastIndexOf('.');
                    if (dot >= 0) {
                        name = name.substring(dot + 1);
                    }
                    if (!name.isEmpty()) {
                        off.add(name);
                    }
                }
                LOGGER.info("[firecontrolcompat] mixins switched off by name: {}", off);
            }
            return off;
        } catch (Exception failure) {
            LOGGER.info("[firecontrolcompat] mixinsOff unreadable: {}", failure.toString());
            return off;
        }
    }



    /**
     * Which quarters of this mod's mixins may be applied, as a list, so the mixin
     * layer can be halved repeatedly without a rebuild.
     *
     * <p>{@code mixinQuarters=1,2} applies only the mixins whose name falls in the
     * first two quarters, {@code mixinQuarters=3,4} the other two, and so on down
     * to a single name. Absent or empty means every mixin is applied as usual.</p>
     */
    private static final java.util.Set<Integer> MIXIN_QUARTERS = readQuarters();

    private static java.util.Set<Integer> readQuarters() {
        java.util.Set<Integer> quarters = new java.util.HashSet<>();
        try {
            java.nio.file.Path path = java.nio.file.Path.of("config/firecontrolcompat-pod.txt");
            if (!java.nio.file.Files.isRegularFile(path)) {
                return quarters;
            }
            for (String line : java.nio.file.Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8)) {
                String text = line.trim().toLowerCase().replace(" ", "");
                if (!text.startsWith("mixinquarters")) {
                    continue;
                }
                int equals = text.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                for (String part : text.substring(equals + 1).split(",")) {
                    try {
                        int value = Integer.parseInt(part.trim());
                        if (value >= 1 && value <= 4) {
                            quarters.add(value);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                LOGGER.info("[firecontrolcompat] mixin quarters in use: {}", quarters);
            }
            return quarters;
        } catch (Exception failure) {
            LOGGER.info("[firecontrolcompat] mixin quarters unreadable, using all: {}", failure.toString());
            return quarters;
        }
    }

    /** The quarter a mixin belongs to: 1..4, steady for a given name. */
    private static int quarterOf(String mixinClassName) {
        String name = mixinClassName == null ? "" : mixinClassName;
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(dot + 1);
        }
        return Math.floorMod(name.hashCode(), 4) + 1;
    }

    private static boolean readMixinsEnabled() {
        try {
            java.nio.file.Path path = java.nio.file.Path.of("config/firecontrolcompat-pod.txt");
            if (!java.nio.file.Files.isRegularFile(path)) {
                return true;
            }
            for (String line : java.nio.file.Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8)) {
                String text = line.trim().toLowerCase().replace(" ", "");
                if (text.startsWith("mixins")) {
                    boolean off = text.endsWith("off") || text.endsWith("false") || text.endsWith("=0");
                    LOGGER.info("[firecontrolcompat] mixin master switch: {}", off ? "OFF" : "on");
                    return !off;
                }
            }
            return true;
        } catch (Exception failure) {
            LOGGER.info("[firecontrolcompat] mixin master switch unreadable, leaving it on: {}", failure.toString());
            return true;
        }
    }


    private static final String TAOV_WEAPONS = "taov_weapons";
    private static final String TAOV_CORE = "taov_core";
    private static final String CBCMORESHELLS = "cbcmoreshells";
    private static final String VESTALIHY = "vestalihy";
    private static final String MIANBAO = "mianbaos_modernwarfare";
    private static final String TERMINAL_BALLISTICS = "cbc_terminal_ballistics";
    private static final String YUUSHYA = "yuushya_modelling";

    private static final String TAOV_WEAPONS_PROBE = "com.verr1.taov.weapons.content.gun.GunBlockEntity";
    private static final String TAOV_CORE_PROBE = "com.verr1.taov.core.weaponhud.WeaponHudBinding";
    private static final String CBCMORESHELLS_PROBE = "com.cainiao1053.cbcmoreshells.blocks.ammo_rack.AmmoRackBlock";
    private static final String VESTALIHY_PROBE = "com.vestalihy.entity.TubusEntity";
    private static final String MIANBAO_PROBE = "net.mcreator.myfirstmod.block.CaissonBlock";
    private static final String TERMINAL_BALLISTICS_PROBE = "com.cbc_terminal_ballistics.CBCTerminalBallistics";
    private static final String YUUSHYA_PROBE = "com.yuushya.modelling.blockentity.showblock.ShowBlockEntity";

    private static final Set<String> TAOV_WEAPONS_MIXINS = Set.of(
            "TaovCbcAmmoMappingMixin",
            "TaovGunInventoryValidationMixin",
            "TaovGunBindingMixin",
            "TaovGunCannonMountMixin",
            "TaovGunBallisticsMixin",
            "HellfireBindingMixin",
            "HellfireAirDefenseCompatMixin");

    private static final Set<String> TAOV_CORE_MIXINS = Set.of(
            "WeaponHudBindingPersistMixin");

    private static final Set<String> CBCMORESHELLS_MIXINS = Set.of(
            "CbcAmmoRackLinkerUseMixin",
            "CbcmsTorpedoSonicMixin",
            "CbcmsTorpedoTubeFireMixin");

    private static final Set<String> VESTALIHY_MIXINS = Set.of(
            "VestalihyLauncherMixin",
            "VestalihyMalytkaLauncherMixin",
            "VestalihyMalytkaRoundMixin",
            "VestalihyMissileCompatMixin",
            "VestalihyGuidanceMixin",
            "VestalihyMissileSonicMixin");

    /**
     * A vanilla client class is not on a dedicated server at all, so these may
     * only be attached where there is a frame being drawn.
     */
    private static final Set<String> CLIENT_ONLY_MIXINS = Set.of(
            "GuidedMunitionRenderMixin");

    /** The modelling blocks only exist with the modelling mod. */
    private static final Set<String> YUUSHYA_MIXINS = Set.of(
            "BlockEntityTypeCreateMixin",
            "YuushyaShowBlockScreenMixin",
            "MountedBigCannonMuzzleMixin");

    /** The spall hooks only make sense while the terminal ballistics mod is installed. */
    private static final Set<String> TERMINAL_BALLISTICS_MIXINS = Set.of(
            "CtbSpallClassifierMixin",
            "CtbSpallFragmentCapMixin");

    /** These only have a target while 面包学 is installed. */
    private static final Set<String> MIANBAO_MIXINS = Set.of(
            "CaissonAmmoMixin",
            "MianbaoGrenadeDecoyLoadMixin",
            "MianbaoGrenadeDecoyFireMixin",
            "MianbaoMissileSonicMixin",
            "MianbaoVisualTorpedoTubeLoadMixin",
            "MianbaoVisualTorpedoGuidanceMixin",
            "AirdroppedTorpedoRackLoadMixin",
            "AirdroppedTorpedoRackFireMixin",
            "MianbaoCruiseRackAircraftCompatMixin",
            "MianbaoCruiseRackSpeedMixin",
            "MianbaoBombRackAircraftCompatMixin",
            // The aircraft-side anti-tank release: 面包学's air-release anti-tank rack (the two
            // "反坦克导弹发射器" blocks 火控学 does not know as ground launchers) is made bindable
            // as an aircraft rack here, and the release itself is the pack's own go procedure.
            "MianbaoAtgmRackAircraftCompatMixin",
            "AtgmAirRackFireMixin",
            // The anti-radiation round is retired: 火控学 ships its own in the next version, so
            // nothing of this mod loads, names or releases that rail any more. The three hooks that
            // did it (AntiRadiationMissileMixin, AntiRadiationRackLoadMixin,
            // AntiRadiationRackAircraftCompatMixin) and the airborne infrared round's loader
            // (AircraftIrFreeFireMixin) are gone from the source tree as well as from this list.
            "GuidedMunitionTrackingMixin");

    /**
     * The EMP blackout hooks one generated procedure class per active protection
     * family.
     *
     * <p>This one is gated on the mod alone, never on the target class: a class
     * probe here cannot see the mod's classes - {@code Class.forName} runs on
     * the mixin plugin's own loader, which does not hold them - so probing
     * answered "absent" for every target and quietly switched the whole
     * blackout off. A procedure that a later 面包学 update renames now only
     * drops its own target with the usual "target was not found" warning.</p>
     */
    private static final String APS_BLACKOUT_MIXIN = "MianbaoApsWorkMixin";

    /**
     * The vibration hooks follow whatever mod owns the machine they listen to.
     *
     * <p>They gate on the <em>mod id</em>, never on the target class. A class
     * probe loads the class together with every supertype it has, and all five
     * of these machines are block entities, so probing one of them loads
     * {@code net.minecraft.world.level.block.entity.BlockEntity}; any mixin
     * prepared after that point whose target is a block entity - here
     * {@code WeaponHudBindingPersistMixin} - then aborts the whole game with
     * {@code MixinTargetAlreadyLoadedException}. Reading the mod list loads
     * nothing.</p>
     */
    private static final Map<String, String> SONIC_MIXIN_MODS = Map.of(
            "CbcCannonFireSonicMixin", "createbigcannons",
            "ShaolibProjectileSpawnSonicMixin", "shaolib",
            "DieselEngineSonicMixin", "createdieselgenerators",
            "GearworkRunningGearMixin", "gearwork",
            "SteamEngineSonicMixin", "create",
            "TracksRunningGearMixin", "tracks");

    private static final String FIRE_CONTROL = "create_fire_control";

    /** 0.8 asks {@code WeaponType#displayName}; 0.7 had a private helper. */
    private static final Set<String> WEAPON_NAME_MODERN_MIXINS = Set.of(
            "AircraftWeaponNameDisplayMixin",
            "AircraftWeaponHudNameMixin");

    private static final Set<String> WEAPON_NAME_LEGACY_MIXINS = Set.of(
            "AircraftWeaponNameMixin");

    private Boolean taovWeaponsPresent;
    private Boolean taovCorePresent;
    private Boolean cbcmoreshellsPresent;
    private Boolean vestalihyPresent;
    private Boolean mianbaoPresent;
    private Boolean terminalBallisticsPresent;
    private Boolean yuushyaPresent;
    private Boolean fireControlEnumNames;

    @Override
    public void onLoad(String mixinPackage) {
        taovWeaponsPresent = modPresent(TAOV_WEAPONS, TAOV_WEAPONS_PROBE);
        taovCorePresent = modPresent(TAOV_CORE, TAOV_CORE_PROBE);
        cbcmoreshellsPresent = modPresent(CBCMORESHELLS, CBCMORESHELLS_PROBE);
        vestalihyPresent = modPresent(VESTALIHY, VESTALIHY_PROBE);
        mianbaoPresent = modPresent(MIANBAO, MIANBAO_PROBE);
        terminalBallisticsPresent = modPresent(TERMINAL_BALLISTICS, TERMINAL_BALLISTICS_PROBE);
        yuushyaPresent = modPresent(YUUSHYA, YUUSHYA_PROBE);
        LOGGER.info("[firecontrolcompat] mixin gate: taov_weapons={} taov_core={} cbcmoreshells={} vestalihy={} mianbao={} terminal_ballistics={} yuushya={} fire_control_enum_weapon_name={}",
                taovWeaponsPresent, taovCorePresent, cbcmoreshellsPresent, vestalihyPresent, mianbaoPresent, terminalBallisticsPresent, yuushyaPresent,
                fireControlNamesWeaponOnEnum());
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!MIXINS_ENABLED) {
            // Master switch: the mod loads, but not one of its mixins is applied.
            return false;
        }
        if (!MIXINS_OFF.isEmpty()) {
            String simple = mixinClassName == null ? "" : mixinClassName;
            int dot = simple.lastIndexOf('.');
            if (dot >= 0) {
                simple = simple.substring(dot + 1);
            }
            if (MIXINS_OFF.contains(simple)) {
                return false;
            }
        }
        if (!MIXIN_QUARTERS.isEmpty() && !MIXIN_QUARTERS.contains(quarterOf(mixinClassName))) {
            return false;
        }
        String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        if (CLIENT_ONLY_MIXINS.contains(name)) {
            return clientSide();
        }
        if (TAOV_WEAPONS_MIXINS.contains(name)) {
            return taovWeapons();
        }
        if (TAOV_CORE_MIXINS.contains(name)) {
            return taovCore();
        }
        if (CBCMORESHELLS_MIXINS.contains(name)) {
            return cbcmoreshells();
        }
        if (VESTALIHY_MIXINS.contains(name)) {
            return vestalihy();
        }
        if (MIANBAO_MIXINS.contains(name)) {
            return mianbao();
        }
        if (APS_BLACKOUT_MIXIN.equals(name)) {
            return mianbao();
        }
        String sonicMod = SONIC_MIXIN_MODS.get(name);
        if (sonicMod != null) {
            return modLoaded(sonicMod);
        }
        if (TERMINAL_BALLISTICS_MIXINS.contains(name)) {
            return terminalBallistics();
        }
        if (YUUSHYA_MIXINS.contains(name)) {
            return yuushya();
        }
        if (WEAPON_NAME_MODERN_MIXINS.contains(name)) {
            return modLoaded(FIRE_CONTROL) && fireControlNamesWeaponOnEnum();
        }
        if (WEAPON_NAME_LEGACY_MIXINS.contains(name)) {
            return modLoaded(FIRE_CONTROL) && !fireControlNamesWeaponOnEnum();
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        if (APS_BLACKOUT_MIXIN.equals(name)) {
            LOGGER.info("[firecontrolcompat] EMP blackout hooked into {}", targetClassName);
        } else if (SONIC_MIXIN_MODS.containsKey(name)) {
            LOGGER.info("[firecontrolcompat] vibration hook heard by {}", targetClassName);
        }
    }

    private static Boolean clientSide;

    /** Whether this is the client half of the game, where the frame is drawn. */
    private static boolean clientSide() {
        if (clientSide == null) {
            try {
                clientSide = FMLLoader.getDist().isClient();
            } catch (Throwable ignored) {
                clientSide = false;
            }
        }
        return clientSide;
    }

    private boolean taovWeapons() {
        if (taovWeaponsPresent == null) {
            taovWeaponsPresent = modPresent(TAOV_WEAPONS, TAOV_WEAPONS_PROBE);
        }
        return taovWeaponsPresent;
    }

    private boolean taovCore() {
        if (taovCorePresent == null) {
            taovCorePresent = modPresent(TAOV_CORE, TAOV_CORE_PROBE);
        }
        return taovCorePresent;
    }

    private boolean cbcmoreshells() {
        if (cbcmoreshellsPresent == null) {
            cbcmoreshellsPresent = modPresent(CBCMORESHELLS, CBCMORESHELLS_PROBE);
        }
        return cbcmoreshellsPresent;
    }

    private boolean vestalihy() {
        if (vestalihyPresent == null) {
            vestalihyPresent = modPresent(VESTALIHY, VESTALIHY_PROBE);
        }
        return vestalihyPresent;
    }

    private boolean terminalBallistics() {
        if (terminalBallisticsPresent == null) {
            terminalBallisticsPresent = modPresent(TERMINAL_BALLISTICS, TERMINAL_BALLISTICS_PROBE);
        }
        return terminalBallisticsPresent;
    }

    private boolean yuushya() {
        if (yuushyaPresent == null) {
            yuushyaPresent = modPresent(YUUSHYA, YUUSHYA_PROBE);
        }
        return yuushyaPresent;
    }

    private boolean mianbao() {
        if (mianbaoPresent == null) {
            mianbaoPresent = modPresent(MIANBAO, MIANBAO_PROBE);
        }
        return mianbaoPresent;
    }

    /**
     * Whether the installed fire control names an aircraft weapon through
     * {@code MianbaoAircraftCompat.WeaponType#displayName} instead of the
     * private {@code aircraftWeaponName} helper it used to carry.
     *
     * <p>0.8 moved the name onto the weapon type enum. The two rename hooks
     * inject into different members, and a redirect whose target is missing
     * aborts mod loading, so exactly one of them may be applied. An unreadable
     * version falls back to the newer shape, which is what this build is
     * compiled against.</p>
     */
    private boolean fireControlNamesWeaponOnEnum() {
        if (fireControlEnumNames == null) {
            String version = fireControlVersion();
            Matcher matcher = version == null ? null : Pattern.compile("(\\d+)\\.(\\d+)").matcher(version);
            if (matcher == null || !matcher.find()) {
                fireControlEnumNames = true;
            } else {
                fireControlEnumNames = Integer.parseInt(matcher.group(1)) > 0
                        || Integer.parseInt(matcher.group(2)) >= 8;
            }
        }
        return fireControlEnumNames;
    }

    /** The fire control version string, or null while it cannot be read. */
    private static String fireControlVersion() {
        try {
            LoadingModList loading = FMLLoader.getLoadingModList();
            if (loading != null) {
                IModFileInfo info = loading.getModFileById(FIRE_CONTROL);
                if (info != null) {
                    return info.versionString();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * True while the named mod is in the loading list. Unlike a class probe
     * this loads nothing, so it can never pull a mixin target in early.
     */
    private static boolean modLoaded(String modId) {
        try {
            LoadingModList loading = FMLLoader.getLoadingModList();
            if (loading != null) {
                return loading.getModFileById(modId) != null;
            }
        } catch (Throwable ignored) {
        }
        try {
            return ModList.get().isLoaded(modId);
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** True while the named class is on the game's class path. */
    private static boolean classPresent(String className) {
        try {
            Class.forName(className, false, FireControlCompatMixinPlugin.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean modPresent(String modId, String probeClass) {
        try {
            LoadingModList loading = FMLLoader.getLoadingModList();
            if (loading != null) {
                return loading.getModFileById(modId) != null;
            }
        } catch (Throwable ignored) {
        }
        try {
            return ModList.get().isLoaded(modId);
        } catch (Throwable ignored) {
        }
        try {
            Class.forName(probeClass, false, FireControlCompatMixinPlugin.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
