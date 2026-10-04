package me.drex.polymerpatcher.companion;

import me.drex.polymerpatcher.resources.ResourceHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which of a block's sounds a companion client plays by itself, and which the server must still send.
 * <p>
 * Polymer's sound patcher mutes the game's own block sounds on every client and has the server play
 * them instead, because an ordinary client would otherwise play its carrier's. A companion client has the
 * real block, but the game's own sounds are still muted for it - so for a block that sounds like stone,
 * the server's copy is the only one it hears. A modded sound is different: the pack defines it, nothing
 * mutes it, and the companion plays it itself the moment it predicts the step or the hit. Sent the
 * server's copy as well, it heard both.
 * <p>
 * So for a companion player each sound the client would play itself is swapped for one nobody sends,
 * and Polymer leaves that player out when the sound is played. Placing is left alone: the companion
 * holds a stand-in item, not the block's own, so it never predicts a placement and has nothing to play.
 */
public final class CompanionSounds {

    private CompanionSounds() {
    }

    /** A sound no client has and the server never sends: "the client handles this one itself". */
    private static final SoundEvent PLAYED_LOCALLY = SoundEvent.createVariableRangeEvent(
        Identifier.fromNamespaceAndPath("polymer-patcher", "companion_played_locally"));

    private static final Map<SoundType, SoundType> HEARD = new ConcurrentHashMap<>();

    /** What a companion client plays itself for this block, or null to leave the question to Polymer. */
    public static @Nullable SoundType heardLocally(ServerPlayer player, BlockState state) {
        if (!CompanionServer.hasBlock(player, state.getBlock())) {
            return null;
        }
        return HEARD.computeIfAbsent(state.getSoundType(), CompanionSounds::forCompanion);
    }

    private static SoundType forCompanion(SoundType real) {
        return new SoundType(real.getVolume(), real.getPitch(),
            ownOrMuted(real.getBreakSound()), ownOrMuted(real.getStepSound()), real.getPlaceSound(),
            ownOrMuted(real.getHitSound()), real.getFallSound());
    }

    /**
     * The sound itself when the client cannot play it (the game's own, muted by the pack), or the marker
     * when it can (a mod's, played by name).
     */
    private static SoundEvent ownOrMuted(SoundEvent sound) {
        return playedLocally(sound) ? PLAYED_LOCALLY : sound;
    }

    /** Whether the client plays this sound itself, for the break sound the patcher plays to everybody. */
    public static boolean playedLocally(SoundEvent sound) {
        Identifier id = sound.location();
        if (Identifier.DEFAULT_NAMESPACE.equals(id.getNamespace()) && ResourceHelper.isGamesOwnSoundEvent(id)) {
            return false;
        }
        // A modded sound the pack has nothing for is silence on the client; the server still sends its stand-in
        return ResourceHelper.hasOwnSoundDefinition(id);
    }

    /** The companion player breaking a block right now, whose own client plays the break sound. */
    public static final ThreadLocal<ServerPlayer> BREAKER = new ThreadLocal<>();
}
