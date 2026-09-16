package com.qkdream.firecontrolcompat;

import java.util.List;
import java.util.Set;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.LoadingModList;
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
 * weapons or CBCMS absent.</p>
 */
public final class FireControlCompatMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("firecontrolcompat");

    private static final String TAOV_WEAPONS = "taov_weapons";
    private static final String TAOV_CORE = "taov_core";
    private static final String CBCMORESHELLS = "cbcmoreshells";

    private static final String TAOV_WEAPONS_PROBE = "com.verr1.taov.weapons.content.gun.GunBlockEntity";
    private static final String TAOV_CORE_PROBE = "com.verr1.taov.core.weaponhud.WeaponHudBinding";
    private static final String CBCMORESHELLS_PROBE = "com.cainiao1053.cbcmoreshells.blocks.ammo_rack.AmmoRackBlock";

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
            "CbcAmmoRackLinkerUseMixin");

    private Boolean taovWeaponsPresent;
    private Boolean taovCorePresent;
    private Boolean cbcmoreshellsPresent;

    @Override
    public void onLoad(String mixinPackage) {
        taovWeaponsPresent = modPresent(TAOV_WEAPONS, TAOV_WEAPONS_PROBE);
        taovCorePresent = modPresent(TAOV_CORE, TAOV_CORE_PROBE);
        cbcmoreshellsPresent = modPresent(CBCMORESHELLS, CBCMORESHELLS_PROBE);
        LOGGER.info("[firecontrolcompat] mixin gate: taov_weapons={} taov_core={} cbcmoreshells={}",
                taovWeaponsPresent, taovCorePresent, cbcmoreshellsPresent);
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        if (TAOV_WEAPONS_MIXINS.contains(name)) {
            return taovWeapons();
        }
        if (TAOV_CORE_MIXINS.contains(name)) {
            return taovCore();
        }
        if (CBCMORESHELLS_MIXINS.contains(name)) {
            return cbcmoreshells();
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
