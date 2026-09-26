package me.drex.polymerpatcher.config;

/** How modded sounds, and the sounds of blocks standing in for modded ones, reach a stranger. */
public class SoundConfig {

    /**
     * Send a modded sound to a stranger as itself rather than as some vanilla sound.
     * <p>
     * A sound packet names its sound by the number it sits at in the registry, and a stranger's
     * registry has only the game's own sounds in it - so a modded sound sent that way is read as
     * whichever vanilla sound happens to live at that number. That is the sound jumble: a mob's call
     * coming out as a door, a chest as something else again.
     * <p>
     * The way out is to send the sound by name instead of by number, which a client can act on because
     * the pack it downloaded defines that name. Only sounds the pack actually carries are sent this
     * way; anything else still falls back to the closest vanilla sound, because a name with nothing
     * behind it is silence.
     */
    public boolean playModdedSounds = true;

    /**
     * Let the server decide what a block sounds like, rather than the block a stranger sees.
     * <p>
     * A modded block is shown to a stranger as some vanilla block chosen for its shape, and the client
     * plays that block's sounds - so walking on a modded path made a cauldron's noise and breaking one
     * sounded like whatever it was standing in for. Turning this on has the server say which sound to
     * play, so a block sounds like itself no matter what it is wearing.
     */
    public boolean serverHandledBlockSounds = true;
}
