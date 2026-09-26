package me.drex.polymerpatcher.config;

import java.util.ArrayList;
import java.util.List;

/** What goes into the resource pack, and what is left out of it. */
public class ResourceConfig {

    /**
     * Leave background music and long ambience out of the generated pack.
     * <p>
     * A pack has to be downloaded before a player can join, and every mod's sounds go into it. On this
     * server that came to 138 MB, of which 115 MB was sound and 69 MB was Enderscape's music alone -
     * so joining meant pulling a hundred-odd megabytes over the host's upload before the world would
     * load. That is why hosting it from the server itself failed for everyone but the host: several
     * clients pulling that at once do not finish before the download gives up.
     * <p>
     * Music and ambience are the only sounds big enough to matter and the only ones nothing depends on
     * - a mob still makes its noise, a block still breaks with its own sound. Turn this off to have
     * them included again.
     */
    public boolean excludeMusicFromPack = true;

    /**
     * Sound folders left out when the above is on. Paths are matched against the start of the sound's
     * own path, so "music" covers everything beneath it.
     */
    public List<String> excludedSoundFolders = new ArrayList<>(List.of("music", "ambient"));

    /**
     * Whether music discs are kept even when the folder they sit in is excluded.
     * <p>
     * A disc is not background music: somebody crafted it, carried it home and put it in a jukebox, and a
     * disc that plays nothing is a thing that looks broken rather than a thing that is quietly absent. So
     * they are kept by default, wherever a mod files them - {@code jukebox}, {@code records},
     * {@code music_disc}.
     * <p>
     * They are also the largest single thing left in the pack once the soundtracks are gone: on this
     * server, sixteen megabytes of a hundred and fourteen. A pack is downloaded again in full whenever it
     * changes, over whatever connection the server has, so on a home connection - or for a player whose
     * own connection keeps dropping - that is worth more than the discs. Turn this off to lose them.
     */
    public boolean includeMusicDiscs = true;

    /**
     * Merge packs dropped into the extra resource pack folder into the pack Polymer hosts.
     * <p>
     * A pack built this way is what actually reaches every player, so anything an admin wants to hand
     * them - textures, language overrides, sounds - has to be rebuilt into a mod unless it is merged in
     * here. Each zip file or folder inside the configured folder is read as a pack; its files merge
     * over the mods' own assets, and anything this mod generates itself merges over them.
     */
    /**
     * Leave out the written pages a client cannot open.
     * <p>
     * Alex's Mobs ships its animal dictionary as nineteen hundred text files, one per animal per language,
     * for a book its own code draws. A client without the mod has no book to put them in, and a client with
     * it already has them - a pack that leaves a file out is a pack the client reads its own copy for. So
     * they are two megabytes and two thousand files of joining time that buy nothing either way.
     */
    public boolean excludeUnreadableText = true;

    /**
     * Ask a mod's own renderer where it puts an item, instead of going by what was written down.
     * <p>
     * An item a mod draws in Java sits wherever that Java puts it, and the model file written for a
     * stranger's client has to say the same thing in its own terms or the item is in the wrong place in
     * the hand. Those steps were read out of the mod's bytecode by hand, one item at a time, into a
     * table - which is accurate, and useless for the next item, and quietly wrong the day the mod
     * changes. The renderer knows all of it and will say so if it is run and watched.
     * <p>
     * An item with nothing written for it takes what its renderer does. An item that has an entry keeps
     * it, and a disagreement between the two is written to the log with both answers in it, so the table
     * can go once the measurements have been seen to match.
     * <p>
     * Turned off, nothing is measured and the table is all there is.
     */
    public boolean measureHeldItemTransforms = true;

    public boolean mergeExtraResourcePacks = true;

    /**
     * Where the extra packs live, relative to the config directory.
     */
    public String extraResourcePacksFolder = "polymer-patcher/resource_packs";
}
