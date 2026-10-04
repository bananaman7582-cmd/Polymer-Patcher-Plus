package me.drex.polymerpatcher.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.item.EquipmentPresentations;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.item.equipment.Equippable;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Makes every component-defined custom glider use the vanilla Elytra renderer.
 *
 * <p>A {@code GLIDER} component is enough to select an Elytra item carrier, but the Elytra renderer
 * asks the equipment asset for a {@code wings} layer. Several mods instead ship a {@code humanoid}
 * layer because their own client replaces the renderer with Java code. A vanilla client therefore
 * receives a real Elytra and still draws no wings. This creates a wings-only equipment asset from the
 * mod's texture and points only the Polymer stand-in at it. Native modded clients keep their original
 * renderer and asset.</p>
 */
public final class GliderFallbacks {
    private static final Map<Item, ResourceKey<EquipmentAsset>> REPLACEMENTS =
        java.util.Collections.synchronizedMap(new IdentityHashMap<>());

    private GliderFallbacks() {
    }

    public static void generate(ResourcePackBuilder builder) {
        Map<Item, ResourceKey<EquipmentAsset>> generated = new IdentityHashMap<>();
        Map<Identifier, Identifier> convertedTextures = new LinkedHashMap<>();

        for (Item item : BuiltInRegistries.ITEM) {
            Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
            if (itemId == null || itemId.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)
                || !item.components().has(DataComponents.GLIDER)) {
                continue;
            }

            Equippable equippable = item.components().get(DataComponents.EQUIPPABLE);
            ResourceKey<EquipmentAsset> originalKey = equippable == null
                ? null : equippable.assetId().orElse(null);
            if (originalKey == null) {
                continue;
            }

            Identifier originalAsset = originalKey.identifier();
            byte[] equipmentBytes = builder.getDataOrSource(equipmentPath(originalAsset));
            JsonObject sourceLayer = null;
            Identifier sourceTexture = null;
            if (equipmentBytes != null) {
                try {
                    JsonObject equipment = JsonParser.parseString(
                        new String(equipmentBytes, StandardCharsets.UTF_8)).getAsJsonObject();
                    JsonObject layers = equipment.getAsJsonObject("layers");
                    if (hasLayer(layers, "wings")) {
                        // It is already a vanilla-readable Elytra asset. The semantic carrier is all
                        // this item needs, and retaining the asset preserves dyes and player textures.
                        continue;
                    }
                    if (hasLayer(layers, "humanoid")) {
                        sourceLayer = layers.getAsJsonArray("humanoid").get(0).getAsJsonObject().deepCopy();
                        sourceTexture = Identifier.tryParse(sourceLayer.get("texture").getAsString());
                    }
                } catch (RuntimeException exception) {
                    PolymerPatcher.LOGGER.debug("Could not read glider equipment asset {}", originalAsset, exception);
                }
            }

            TextureSource texture = findTexture(builder, itemId, originalAsset, sourceTexture);
            if (texture == null) {
                PolymerPatcher.LOGGER.info("Custom glider {} has no vanilla wings layer or readable texture; "
                    + "it will still use Elytra behavior but keep its original equipment asset", itemId);
                continue;
            }

            Identifier targetTexture = convertedTextures.computeIfAbsent(texture.id(), ignored ->
                PolymerPatcher.id("glider/" + itemId.getNamespace() + "/" + itemId.getPath()));
            builder.addData(texturePath("wings", targetTexture), toVanillaWingTexture(texture.bytes()));

            JsonObject layer = sourceLayer == null ? new JsonObject() : sourceLayer;
            layer.addProperty("texture", targetTexture.toString());
            Identifier targetAsset = PolymerPatcher.id(
                "glider/" + itemId.getNamespace() + "/" + itemId.getPath());
            builder.addData(equipmentPath(targetAsset), wingsEquipmentJson(layer));
            generated.put(item, ResourceKey.create(EquipmentAssets.ROOT_ID, targetAsset));
        }

        synchronized (REPLACEMENTS) {
            REPLACEMENTS.clear();
            REPLACEMENTS.putAll(generated);
        }
        if (!generated.isEmpty()) {
            PolymerPatcher.LOGGER.info("Built vanilla Elytra equipment assets for {} custom glider(s): {}",
                generated.size(), generated.keySet().stream()
                    .map(BuiltInRegistries.ITEM::getKey).map(String::valueOf).sorted().toList());
        }
    }

    private static boolean hasLayer(JsonObject layers, String name) {
        return layers != null && layers.has(name) && layers.get(name).isJsonArray()
            && !layers.getAsJsonArray(name).isEmpty();
    }

    private record TextureSource(Identifier id, byte[] bytes) {
    }

    private static TextureSource findTexture(ResourcePackBuilder builder, Identifier itemId,
                                             Identifier asset, Identifier named) {
        if (named != null) {
            byte[] modern = builder.getDataOrSource(texturePath("humanoid", named));
            if (modern != null) {
                return new TextureSource(named, modern);
            }
        }

        for (Identifier candidate : new Identifier[] {
            Identifier.fromNamespaceAndPath(asset.getNamespace(), asset.getPath()),
            Identifier.fromNamespaceAndPath(itemId.getNamespace(), itemId.getPath())
        }) {
            byte[] legacy = builder.getDataOrSource("assets/" + candidate.getNamespace()
                + "/textures/armor/" + candidate.getPath() + ".png");
            if (legacy != null) {
                return new TextureSource(candidate, legacy);
            }
        }
        return null;
    }

    static byte[] wingsEquipmentJson(JsonObject layer) {
        JsonArray wings = new JsonArray();
        wings.add(layer);
        JsonObject layers = new JsonObject();
        layers.add("wings", wings);
        JsonObject root = new JsonObject();
        root.add("layers", layers);
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Converts common custom 64x64 Elytra layouts to the vanilla model's 64x32 UV layout. */
    static byte[] toVanillaWingTexture(byte[] source) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
            if (image == null || image.getWidth() == 64 && image.getHeight() == 32) {
                return source;
            }

            BufferedImage out = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = out.createGraphics();
            graphics.setComposite(AlphaComposite.Src);

            Bounds bounds = opaqueBounds(image);
            if (image.getWidth() >= 64 && image.getHeight() >= 64 && bounds != null
                && bounds.minY() >= 32) {
                // ModelAMElytra-style layers use the same 10x20x2 cuboids as vanilla, but begin at
                // UV 32,32 instead of 22,0. Moving that exact quadrant retains the modded artwork
                // without stretching it over the vanilla wing geometry.
                int targetX = bounds.minX() >= 32 ? 22 : 0;
                int sourceX = bounds.minX() >= 32 ? 32 : 0;
                graphics.drawImage(image, targetX, 0, targetX + 32, 32,
                    sourceX, 32, sourceX + 32, 64, null);
            } else {
                graphics.drawImage(image, 0, 0, 64, 32, 0, 0,
                    image.getWidth(), image.getHeight(), null);
            }
            graphics.dispose();

            try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                ImageIO.write(out, "png", bytes);
                return bytes.toByteArray();
            }
        } catch (Throwable exception) {
            PolymerPatcher.LOGGER.debug("Could not normalize a custom glider texture", exception);
            return source;
        }
    }

    private record Bounds(int minX, int minY, int maxX, int maxY) {
    }

    private static Bounds opaqueBounds(BufferedImage image) {
        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return maxX < 0 ? null : new Bounds(minX, minY, maxX, maxY);
    }

    public static void modifyItemStack(ItemStack out, ItemStack original) {
        if (!EquipmentPresentations.isGlider(original)) {
            return;
        }
        Equippable equippable = original.get(DataComponents.EQUIPPABLE);
        if (equippable == null) {
            return;
        }
        ResourceKey<EquipmentAsset> asset = REPLACEMENTS.get(original.getItem());
        if (asset == null) {
            // Preserve a mod-provided wings asset rather than inheriting minecraft:elytra merely
            // because the carrier itself is an Elytra.
            out.set(DataComponents.EQUIPPABLE, equippable);
            return;
        }
        out.set(DataComponents.EQUIPPABLE, new Equippable(
            equippable.slot(), equippable.equipSound(), Optional.of(asset), equippable.cameraOverlay(),
            equippable.allowedEntities(), equippable.dispensable(), equippable.swappable(),
            equippable.damageOnHurt(), equippable.equipOnInteract(), equippable.canBeSheared(),
            equippable.shearingSound()
        ));
    }

    private static String equipmentPath(Identifier asset) {
        return "assets/" + asset.getNamespace() + "/equipment/" + asset.getPath() + ".json";
    }

    private static String texturePath(String layer, Identifier texture) {
        return "assets/" + texture.getNamespace() + "/textures/entity/equipment/" + layer + "/"
            + texture.getPath() + ".png";
    }
}
