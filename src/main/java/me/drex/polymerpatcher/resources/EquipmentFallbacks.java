package me.drex.polymerpatcher.resources;

import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.item.equipment.Equippable;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Puts a mod's armour back on the player for somebody without the mod.
 * <p>
 * Armour is drawn from an equipment file: the item names one, and the file says which pictures to lay over
 * the player's body. A mod written before that existed keeps its armour pictures where armour pictures used
 * to go - {@code textures/armor/hazmat_suit_0.png} - and builds the equipment file in its own client code as
 * the game starts. That code does not run on anybody else's client and the file is in no pack, so the item
 * names an equipment file nothing can find: the hazmat suit, the diving suit, the gingerbread armour and the
 * hood and cloak of darkness were all worn invisibly.
 * <p>
 * The pictures themselves are in the pack already, in the old place. So the missing file is written here from
 * them - the first picture over body, arms and feet, the second over the legs, which is what those two files
 * have always meant - and a stand-in is pointed at it. A client that does have the mod is untouched and goes
 * on using the mod's own.
 */
public final class EquipmentFallbacks {

    private EquipmentFallbacks() {
    }

    /** Where a mod written for an older game keeps its armour pictures. */
    private static final String LEGACY_FOLDER = "textures/armor/";

    /** The equipment file a stand-in should name instead, by the one the mod's item names. */
    private static final Map<Identifier, ResourceKey<EquipmentAsset>> REPLACEMENTS = new ConcurrentHashMap<>();

    /**
     * Writes an equipment file for every piece of modded armour whose mod did not ship one.
     */
    public static void generate(ResourcePackBuilder builder) {
        Map<String, Set<String>> legacyTextures = findLegacyTextures(builder);
        if (legacyTextures.isEmpty()) {
            return;
        }

        Map<Identifier, String> written = new LinkedHashMap<>();
        Set<Identifier> unmatched = new TreeSet<>();

        for (Item item : BuiltInRegistries.ITEM) {
            Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
            if (itemId == null || !PolymerPatcher.PATCHED_MODS.contains(itemId.getNamespace())) {
                continue;
            }

            Equippable equippable = item.components().get(DataComponents.EQUIPPABLE);
            ResourceKey<EquipmentAsset> assetKey = equippable == null ? null : equippable.assetId().orElse(null);
            if (assetKey == null) {
                continue;
            }

            Identifier asset = assetKey.identifier();
            // Only what this build has already dealt with. Deliberately not what an earlier build did: the
            // pack can be built again while the server runs, and the second pack needs these files as much
            // as the first did
            if (written.containsKey(asset) || unmatched.contains(asset)) {
                continue;
            }
            // The mod shipped its own; there is nothing to stand in for
            if (builder.getDataOrSource(equipmentPath(asset)) != null) {
                continue;
            }

            String base = matchLegacyTexture(legacyTextures.getOrDefault(asset.getNamespace(), Set.of()),
                asset.getPath(), itemId.getPath());
            if (base != null && write(builder, asset, base)) {
                written.put(asset, base);
            } else {
                unmatched.add(asset);
            }
        }

        if (!written.isEmpty()) {
            PolymerPatcher.LOGGER.info("Wrote {} equipment file(s) so modded armour is visible to clients without the mod: {}",
                written.size(), written);
        }
        if (!unmatched.isEmpty()) {
            PolymerPatcher.LOGGER.info("{} piece(s) of modded armour name an equipment file that is in no pack, and have no old-style pictures to build one from: {}",
                unmatched.size(), unmatched);
        }
    }

    /**
     * The picture files each mod keeps in the old armour folder, by mod, with the layer number taken off.
     */
    private static Map<String, Set<String>> findLegacyTextures(ResourcePackBuilder builder) {
        Map<String, Set<String>> found = new HashMap<>();
        builder.forEachResource((path, resource) -> {
            if (!path.startsWith("assets/") || !path.endsWith(".png")) {
                return;
            }
            String[] parts = path.split("/", 3);
            if (parts.length != 3 || !parts[2].startsWith(LEGACY_FOLDER)) {
                return;
            }
            if (!PolymerPatcher.PATCHED_MODS.contains(parts[1])) {
                return;
            }
            String name = parts[2].substring(LEGACY_FOLDER.length(), parts[2].length() - ".png".length());
            found.computeIfAbsent(parts[1], ignored -> new HashSet<>()).add(name);
        });
        return found;
    }

    /**
     * The picture belonging to this equipment file, by name.
     * <p>
     * A mod's equipment file is named after the material rather than the piece - "hazmat_suit" for a whole
     * suit - and the pictures follow the same name with a layer number after it. That is the ordinary case;
     * where the two do not match exactly the item's own name settles it, because a piece of armour is named
     * after the same material it is made of.
     */
    private static @Nullable String matchLegacyTexture(Set<String> textures, String assetPath, String itemPath) {
        String best = null;
        int bestScore = 0;

        for (String texture : textures) {
            String base = texture.endsWith("_0") || texture.endsWith("_1")
                ? texture.substring(0, texture.length() - 2)
                : texture;

            int score;
            if (base.equals(assetPath)) {
                score = 4;
            } else if (base.startsWith(assetPath) || assetPath.startsWith(base)) {
                score = 3;
            } else if (itemPath.startsWith(firstWord(base))) {
                // hazmat_boots against hazmat_suit_0: the material's name is the first word of both
                score = 2;
            } else {
                score = 0;
            }

            if (score > bestScore) {
                bestScore = score;
                best = base;
            }
        }

        return best;
    }

    private static String firstWord(String name) {
        int underscore = name.indexOf('_');
        return underscore <= 0 ? name : name.substring(0, underscore);
    }

    /**
     * Copies the old pictures to where the game looks for equipment pictures, and writes the file naming them.
     */
    private static boolean write(ResourcePackBuilder builder, Identifier asset, String base) {
        String folder = "assets/" + asset.getNamespace() + "/" + LEGACY_FOLDER;
        byte[] body = builder.getDataOrSource(folder + base + "_0.png");
        if (body == null) {
            body = builder.getDataOrSource(folder + base + ".png");
        }
        byte[] legs = builder.getDataOrSource(folder + base + "_1.png");
        if (body == null && legs == null) {
            return false;
        }

        // Named after the mod as well as the file it stands in for, so two mods each with a "hazmat_suit"
        // cannot land on top of each other
        Identifier ours = PolymerPatcher.id(asset.getNamespace() + "/" + base);

        StringBuilder layers = new StringBuilder("{\"layers\":{");
        if (body != null) {
            builder.addData(equipmentTexturePath("humanoid", ours), body);
            layers.append("\"humanoid\":[{\"texture\":\"").append(ours).append("\"}]");
        }
        if (legs != null) {
            if (body != null) {
                layers.append(',');
            }
            builder.addData(equipmentTexturePath("humanoid_leggings", ours), legs);
            layers.append("\"humanoid_leggings\":[{\"texture\":\"").append(ours).append("\"}]");
        }
        layers.append("}}");

        builder.addData(equipmentPath(ours), layers.toString().getBytes(StandardCharsets.UTF_8));
        REPLACEMENTS.put(asset, ResourceKey.create(EquipmentAssets.ROOT_ID, ours));
        return true;
    }

    /**
     * Points a stand-in at the equipment file written for it, where there is one.
     */
    public static void modifyItemStack(ItemStack out, ItemStack original) {
        Equippable equippable = out.get(DataComponents.EQUIPPABLE);
        if (equippable == null) {
            return;
        }
        ResourceKey<EquipmentAsset> named = equippable.assetId().orElse(null);
        if (named == null) {
            return;
        }
        ResourceKey<EquipmentAsset> ours = REPLACEMENTS.get(named.identifier());
        if (ours == null) {
            return;
        }

        out.set(DataComponents.EQUIPPABLE, new Equippable(
            equippable.slot(),
            equippable.equipSound(),
            Optional.of(ours),
            equippable.cameraOverlay(),
            equippable.allowedEntities(),
            equippable.dispensable(),
            equippable.swappable(),
            equippable.damageOnHurt(),
            equippable.equipOnInteract(),
            equippable.canBeSheared(),
            equippable.shearingSound()
        ));
    }

    private static String equipmentPath(Identifier asset) {
        return "assets/" + asset.getNamespace() + "/equipment/" + asset.getPath() + ".json";
    }

    private static String equipmentTexturePath(String layer, Identifier texture) {
        return "assets/" + texture.getNamespace() + "/textures/entity/equipment/" + layer + "/" + texture.getPath() + ".png";
    }
}
