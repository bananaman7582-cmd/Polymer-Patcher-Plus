package me.drex.polymerpatcher.block;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Blocks that hold something up for you to look at, and where they hold it.
 * <p>
 * A block that displays its contents does so from a block entity renderer, and a dedicated server runs
 * none - so to anyone without the mod these are opaque shells. An Alex's Mobs capsid gives no sign
 * whether anything is inside it or whether the transformation has happened; an Alex's Caves abyssal
 * altar, whose entire purpose is showing what has been offered on it, shows nothing at all.
 * <p>
 * Each is listed with where its own renderer puts the item, read off that renderer rather than guessed,
 * so what a stranger sees sits where a player with the mod sees it. Adding another is one line.
 */
public final class ShowcaseBlocks {

    private ShowcaseBlocks() {
    }

    /**
     * @param height how far above the middle of the block the item sits
     * @param scale  how big it is drawn
     * @param flat   whether it lies face-up, as on an altar, rather than standing
     */
    public record Showcase(float height, float scale, boolean flat) {
    }

    private static final Map<Identifier, Showcase> BLOCKS = new ConcurrentHashMap<>();

    /** Registers where a block-entity renderer displays its contained item. */
    public static void register(Identifier block, Showcase showcase) {
        BLOCKS.put(block, showcase);
    }

    /** Whether this block is one whose contents are worth drawing for a stranger. */
    public static boolean showsWhatIsInside(BlockState state) {
        return showcaseFor(state) != null;
    }

    /** A model drawing what this block is holding, or nothing if it is not one of them. */
    @Nullable
    public static ElementHolder modelFor(BlockState state) {
        Showcase showcase = showcaseFor(state);
        return showcase == null ? null : new ContainerShowcaseModel(3.0F, showcase.height(), showcase.scale(), showcase.flat());
    }

    @Nullable
    private static Showcase showcaseFor(BlockState state) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id == null ? null : BLOCKS.get(id);
    }
}
