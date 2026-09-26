package me.drex.polymerpatcher.dump.data;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.Item;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class RenderRegistry {
    /** Incremented when a dump gains data an older file cannot contain. */
    public static final int CURRENT_FORMAT_VERSION = 2;

    public int formatVersion = CURRENT_FORMAT_VERSION;
    public final List<RenderInfo> entityData = new LinkedList<>();
    public final Map<ModelLayerLocation, LayerDefinition> modelLayers = new HashMap<>();
    public final List<BlockInfo> blockData = new LinkedList<>();

    /**
     * Models actually selected by Fabric armor renderers for their registered items.
     *
     * <p>The model layer name is not required to resemble the item name. Recording the association
     * while the renderer makes it is what lets this work for arbitrary mods instead of maintaining
     * guesses or per-mod item lists.</p>
     */
    public final List<ArmorInfo> armorData = new LinkedList<>();

    /**
     * Which mods this dump was taken with, so both sides can tell whether it still describes the pack.
     * <p>
     * A dump only covers the mods that were installed when it was taken, and a mod added afterwards is
     * simply missing from it - which shows up as that mod's mobs being invisible, with nothing to say
     * why. Recording the namespaces makes that answerable: the client can tell its dump is out of date
     * and take another, and the server can say so plainly rather than quietly rendering nothing.
     * <p>
     * Namespaces rather than mod ids, because the two sides do not run the same mods - a client also
     * carries the likes of Sodium, which own no content and would otherwise never match.
     */
    public Set<String> namespaces = new TreeSet<>();

    public transient final Map<Block, BlockInfo> blockInfoByBlock = new HashMap<>();

    /**
     * The namespaces that own content right now, on whichever side is asking.
     */
    public static Set<String> currentNamespaces() {
        Set<String> namespaces = new TreeSet<>();
        BuiltInRegistries.ENTITY_TYPE.keySet().forEach(id -> namespaces.add(id.getNamespace()));
        BuiltInRegistries.BLOCK.keySet().forEach(id -> namespaces.add(id.getNamespace()));
        BuiltInRegistries.ITEM.keySet().forEach(id -> namespaces.add(id.getNamespace()));
        namespaces.remove(Identifier.DEFAULT_NAMESPACE);
        return namespaces;
    }

    /**
     * The content namespaces this side has that the dump knows nothing about.
     */
    public Set<String> missingNamespaces() {
        Set<String> missing = new TreeSet<>(currentNamespaces());
        missing.removeAll(namespaces);
        return missing;
    }

    public record RenderInfo(
        EntityType type,
        Class<? extends EntityRenderer> entityRenderer,
        Set<ModelLayerLocation> modelLayers,
        Set<Identifier> textures
    ) {
    }

    public record BlockInfo(Block block, ColorResolver biomeColor, boolean transparent) {
    }

    public record ArmorInfo(Item item, ModelLayerLocation modelLayer, Identifier texture) {
    }

    public boolean isCurrentFormat() {
        return formatVersion >= CURRENT_FORMAT_VERSION;
    }

    public void rebuildIndexes() {
        blockInfoByBlock.clear();
        for (BlockInfo blockDatum : blockData) {
            blockInfoByBlock.put(blockDatum.block, blockDatum);
        }
    }

}
