package me.drex.polymerpatcher.compat;

import me.drex.polymerpatcher.compat.alexscaves.AlexsCavesBiomeLight;
import me.drex.polymerpatcher.compat.alexscaves.AlexsCavesItemTints;
import me.drex.polymerpatcher.compat.alexscaves.AlexsCavesRenderRules;
import me.drex.polymerpatcher.compat.alexscaves.AlexsCavesStackIdentity;
import me.drex.polymerpatcher.compat.alexscaves.CaveMaps;
import me.drex.polymerpatcher.compat.alexscaves.ExtinctionSpearEffects;
import me.drex.polymerpatcher.compat.alexscaves.MagneticCrackEffects;
import me.drex.polymerpatcher.compat.alexscaves.MagneticPull;
import me.drex.polymerpatcher.compat.alexscaves.NuclearCloud;
import me.drex.polymerpatcher.compat.alexscaves.NuclearFurnaceCompat;
import me.drex.polymerpatcher.compat.alexscaves.NucleeperSiren;
import me.drex.polymerpatcher.compat.alexscaves.RaygunBeam;
import me.drex.polymerpatcher.compat.alexscaves.ResistorShieldEffects;
import me.drex.polymerpatcher.compat.alexscaves.SpiritGrip;
import me.drex.polymerpatcher.compat.alexsmobs.AlexsMobsCompatibility;
import me.drex.polymerpatcher.compat.borrowedecho.BorrowedEchoPlayerSkins;
import me.drex.polymerpatcher.compat.enderscape.EnderscapeCompatibility;
import me.drex.polymerpatcher.compat.illagerinvasion.IllagerInvasionCompatibility;
import me.drex.polymerpatcher.compat.neverend.NeverendClientEffects;
import me.drex.polymerpatcher.compat.neverend.NeverendCompatibility;
import me.drex.polymerpatcher.compat.sculkhorde.SculkHordeClientEffects;
import me.drex.polymerpatcher.dump.data.RenderRegistry;
import net.minecraft.server.level.ServerPlayer;

/**
 * Lifecycle boundary for facts that truly belong to one mod.
 *
 * <p>Compat classes register with global presentation engines; core code no longer contains their
 * renderer class names, item ids or volatile data keys. Keeping the phases here also makes it clear
 * which rules can be generalized and which require knowledge only the owning mod can provide.</p>
 */
public final class CompatibilityModules {
    private CompatibilityModules() {
    }

    /** Hooks which must exist before resource-pack construction is registered. */
    public static void initBeforeResources() {
        AlexsCavesStackIdentity.init();
        NuclearFurnaceCompat.init();
        IllagerInvasionCompatibility.init();
        AlexsCavesItemTints.init();
        AlexsCavesBiomeLight.init();
        AlexsMobsCompatibility.init();
        EnderscapeCompatibility.init();
        NeverendCompatibility.init();
    }

    /** Hooks which intentionally run after the common resource loader has been registered. */
    public static void initAfterResources() {
        BorrowedEchoPlayerSkins.init();
        NuclearCloud.init();
        NucleeperSiren.init();
        ResistorShieldEffects.init();
        MagneticCrackEffects.init();
        MagneticPull.init();
        SpiritGrip.init();
        ExtinctionSpearEffects.init();
        RaygunBeam.init();
        CaveMaps.init();
        SculkHordeClientEffects.init();
    }

    public static void setupRendering(RenderRegistry registry) {
        AlexsCavesRenderRules.init();
        AlexsMobsCompatibility.setupRendering();
        NeverendCompatibility.setupRendering(registry);
    }

    public static void forget(ServerPlayer player) {
        NeverendClientEffects.forget(player);
    }
}
