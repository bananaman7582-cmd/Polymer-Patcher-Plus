package me.drex.polymerpatcher.sound;

import eu.pb4.polymer.soundpatcher.api.SoundPatcher;
import eu.pb4.polymer.soundpatcher.impl.SoundRemapperImpl;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.config.ConfigManager;
import me.drex.polymerpatcher.resources.ResourceHelper;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Makes a sound reach a stranger as the sound it actually is.
 * <p>
 * Two separate things were making the wrong noise come out, and they are worth keeping apart because
 * they fail differently.
 * <p>
 * The first is modded sounds. A sound packet names its sound by the number it sits at in the server's
 * registry, and a stranger's registry holds only the game's own sounds - so a modded sound sent that
 * way is read as whichever vanilla sound happens to live at that number. Polymer's sound patcher can
 * send a sound <em>by name</em> instead, which a client can act on because the pack it downloaded
 * defines that name.
 * <p>
 * The second is block sounds, which are not sent at all: the client works out what a block sounds like
 * from the block it can see. A modded block is shown as some vanilla block picked for its shape, so
 * the client played that block's sounds - a modded path walked on like a cauldron. Asking the server
 * to say which sound to play takes the stand-in out of the decision entirely.
 * <p>
 * Neither of these replaces the vanilla stand-in every modded sound is also given. That stand-in is
 * what registry data is rewritten with, and registry data is read while a player is still joining.
 */
public final class SoundPatching {

    private SoundPatching() {
    }

    /** Sounds that will be played by name, kept so the count can be said once rather than per sound. */
    private static final Set<Identifier> SENT_AS_THEMSELVES = new LinkedHashSet<>();

    private static boolean remapperUnavailable;

    /**
     * Takes the stand-in block out of the question of what a block sounds like.
     * <p>
     * Done from here rather than left to Polymer's own config file, because that setting defaults to
     * off and a server that loses it goes straight back to making a cauldron's noise underfoot.
     */
    public static void handleBlockSoundsOnTheServer() {
        if (!ConfigManager.config().sounds.serverHandledBlockSounds) {
            return;
        }
        try {
            SoundPatcher.convertAllVanillaBlockSoundsIntoServerSounds();
            PolymerPatcher.LOGGER.info("Block sounds are decided by the server, so a modded block no longer sounds like whatever it is standing in for");
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.warn("Could not take over block sounds; a modded block will sound like the block it stands in for", e);
        }
    }

    /**
     * Arranges for this sound to be <em>played</em> by name rather than by registry number.
     * <p>
     * This is about sound packets only, and it does not replace the vanilla stand-in the caller
     * registers alongside it. Registry data - a biome's ambience, a dimension's music, a jukebox
     * song's track - names its sounds by id, and a joining client resolves those against its own
     * registry, where a modded name is not found and the whole registry load fails with it. Leaving
     * the stand-in off for sounds handled here is exactly what stopped anyone joining at all.
     * <p>
     * Doing nothing is not a failure - it means the pack has nothing behind the name, and a name with
     * no audio behind it is silence.
     */
    public static void sendByNameWherePossible(Identifier identifier) {
        if (!ConfigManager.config().sounds.playModdedSounds || remapperUnavailable) {
            return;
        }
        // A name with no definition behind it plays nothing at all, so those are left to the vanilla
        // stand-in entirely
        if (!ResourceHelper.hasOwnSoundDefinition(identifier)) {
            return;
        }

        try {
            // Registering a sound against itself is what makes Polymer send it as a value rather than
            // as a number: the remapper hands back a fresh sound object, and the packet codec sends
            // anything it did not get back unchanged by name
            SoundRemapperImpl.register(identifier, identifier);
            SENT_AS_THEMSELVES.add(identifier);
        } catch (Throwable e) {
            // One warning, then the vanilla stand-in for everything - this is Polymer's inner
            // machinery and a version that has moved it should cost sounds, not the server
            remapperUnavailable = true;
            PolymerPatcher.LOGGER.warn("Could not arrange for modded sounds to be played by name; each will be heard as the closest vanilla sound instead", e);
        }
    }

    /** Said once, after the sweep, rather than a line per sound. */
    public static void report() {
        if (!SENT_AS_THEMSELVES.isEmpty()) {
            PolymerPatcher.LOGGER.info("{} modded sound(s) will be heard as themselves when played; every modded sound still has a vanilla stand-in for the registry data that names it", SENT_AS_THEMSELVES.size());
        }
    }
}
