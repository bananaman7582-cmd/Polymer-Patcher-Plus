package me.drex.polymerpatcher.config;

import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BlockConfig {

    /**
     * Send a modded fluid as water, so that a client swims in it.
     * <p>
     * A client works out for itself whether it is in a fluid, from the block it was sent. Sent air - which
     * is what a modded fluid used to be, with the fluid drawn over the top of it - it walks and falls as
     * though the pool were not there, and everything about swimming up a pool of acid is the server
     * arguing with it afterwards. Sent water at the same depth, it swims.
     * <p>
     * The fluid is still drawn by this mod, over the water and a little taller than it, so it still looks
     * like itself. What water brings with it is its own underwater view: the tint and fog of the biome the
     * pool is in, which is the one part of this a server cannot choose per block.
     */
    public boolean moddedFluidsAsWater = true;

    /**
     * Draw a modded fluid at the height the game draws its own, rather than a little shorter.
     * <p>
     * The client is sent water underneath so the fluid can be swum in, and a surface drawn short of that
     * water puts the water's own surface on top - an acid lake looked down on is water, with acid
     * underneath. Matched exactly, the water is hidden behind ours on every face.
     * <p>
     * Enabled by default. This only changes generated model dimensions; it deliberately does not inspect
     * neighbouring blocks while chunk attachments are being created. The old shorter surface exposes a
     * thin strip of the water carrier above every custom liquid.
     */
    public boolean fluidSurfacesMatchGameHeights = true;

    /**
     * Whether a modded fluid's surface gets a wall where the fluid beside it is lower or missing, as the
     * game draws one. A block of fluid cannot see its neighbours, so without this the step down from a
     * full block to the flow beside it was a slit you could see the ground through. Only cells with a
     * side actually exposed get anything, so the inside of a lake costs nothing.
     */
    public boolean fluidEdgeWalls = true;

    /**
     * Fluids to leave as they were: drawn as themselves, with nothing underneath them.
     * <p>
     * Water is what makes a client swim, and water is blue. A fluid this mod draws opaquely hides it
     * completely; one you can see through shows it, which is the whole of the trade. Name a fluid here and
     * it keeps its own look and loses the swimming - {@code "alexscaves:acid"}, say, if the blue behind it
     * bothers you more than walking through it does.
     */
    public List<String> fluidsKeptAsThemselves = new ArrayList<>();

    /**
     * Fluids whose texture is spread across a square of blocks rather than repeated on every one, and how
     * many blocks across that square is.
     * <p>
     * The Sift draws ichor this way: its texture is 256 pixels a side, and each block shows only its own
     * sixteenth of it, chosen by where the block is - so a whole lake reads as one surface. Drawn on every
     * block whole, it came out sixteen times too small. Nothing in a mod's assets says a fluid does this;
     * it lives only in the mod's own client code, which is why it is written down here. Each fluid listed
     * costs a model for every place in its square, at every depth - 256 of them for a square 16 across.
     * <p>
     * Empty by default. The Sift's ichor, {@code "the_sift:ichor": 16}, is the one fluid known to need it,
     * and even there only the in-fluid view came out right; its surface stayed patchy. Treat an entry here
     * as something to try, not something known to work.
     */
    public Map<String, Integer> fluidWorldTiles = new HashMap<>();
    public int defaultWeight = 1000;

    public Map<Identifier, Integer> weights = new HashMap<>() {{

    }};

    public List<RegexWeight> regexWeights = new ArrayList<>(){{
        add(new RegexWeight(".*_leaves", 250));
        add(new RegexWeight(".*_log", 500));
        add(new RegexWeight(".*_wood", 750));
    }};

    public record RegexWeight(String regex, int weight) {}

    /**
     * How far away a modded block drawn by a display is still drawn, in the game's own unit for display
     * entities: multiples of 64 blocks, further scaled by each player's entity distance setting.
     * <p>
     * Every block that could not be given a vanilla carrier is a display entity, and a cave biome is full of
     * them - vines, ferns, branches, eggs. At 3.0 every one within 192 blocks was drawn every frame, and a
     * primordial cave ran at ten frames a second. 1.5 still reaches past anything a cave lets you see.
     */
    public float displayViewRange = 1.5F;

    /**
     * How far out a see-through, walk-through block is drawn when it is a display only because every
     * carrier that would have fitted it was already taken - hanging vines, ivy, the plants of a mod that
     * arrived after the carriers ran out.
     * <p>
     * These used to be shown as the nearest vanilla plant instead - willow vines as acacia saplings, ivy as
     * mushrooms - which cost nothing and looked wrong. Drawn properly they cost a display each, and there
     * can be a great many of them, so they are drawn only this far out. 0.5 is about 32 blocks at the
     * game's normal entity distance.
     */
    public float fallbackDisplayViewRange = 0.5F;

    /**
     * The same for small decorations - anything whose shape is under half a block, such as plants, vines,
     * eggs and small lights. Those are hard to make out at a distance anyway and are by far the most
     * numerous, so they are drawn out to 48 blocks rather than 96.
     */
    public float smallDisplayViewRange = 0.75F;


    /**
     * Whether a modded block's own ambient particles - what its animateTick draws on a client - are drawn
     * for players near it. Every client is sent a carrier in place of a modded block, and a carrier runs
     * its own ambience rather than the mod's, so without this nobody sees them. Sculk Horde's flora
     * shedding crust is one; a glowing ore's sparks would be another.
     */
    public boolean replayModdedBlockAmbience = true;

    /** Mods whose block ambience is left alone, for one that turns out to be too busy. */
    public List<String> blockAmbienceExcludedMods = new ArrayList<>();

    /**
     * Whether a modded slab left without a carrier is shown as the nearest vanilla slab rather than drawn
     * by a display. Off: it is shown as itself. On trades its look for no display, which only matters for
     * slabs placed by the thousand - there are just eight carrier states for the upper half of a block.
     */
    public boolean vanillaSlabsWhenOutOfCarriers = false;

    /**
     * Whether block displays and the real-block list Jade reads are caught up for chunks that reached a
     * player without passing through vanilla's chunk sending. A portal mod built on Immersive Portals sends
     * chunks itself, and without this every display-drawn block in them stays invisible. Costs a look over
     * each player's loaded chunks once a second.
     */
    public boolean repairChunkSync = true;

    /**
     * Whether distant terrain sent to Voxy players - by Voxy World Gen V2 or Voxy Server Side - shows modded
     * blocks as the carriers players see up close, rather than as the wrong vanilla block or as stone. Voxy
     * Server Side rebuilds the terrain it keeps on disk once after this changes. See
     * {@link me.drex.polymerpatcher.util.DistantTerrain}.
     */
    public boolean distantTerrainCarriers = true;
}
