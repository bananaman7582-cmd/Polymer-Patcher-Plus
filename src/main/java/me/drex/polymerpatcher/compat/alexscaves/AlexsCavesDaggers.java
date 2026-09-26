package me.drex.polymerpatcher.compat.alexscaves;

import com.google.gson.JsonObject;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Vanilla-readable appearance for Alex's Caves' code-tinted flying desolate daggers. */
public final class AlexsCavesDaggers {

    private AlexsCavesDaggers() {
    }

    private static final Identifier ENTITY = Identifier.fromNamespaceAndPath("alexscaves", "desolate_dagger");
    private static final Identifier PROJECTILE_MODEL = PolymerPatcher.id("item/desolate_dagger_projectile");

    public static ItemStack projectileStack(Entity entity, ItemStack original) {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (!ENTITY.equals(id)) {
            return original;
        }
        ItemStack copy = original.copy();
        copy.set(DataComponents.ITEM_MODEL, PROJECTILE_MODEL);
        return copy;
    }

    public static void generate(ResourcePackBuilder builder) {
        byte[] source = builder.getDataOrSource("assets/alexscaves/textures/item/desolate_dagger.png");
        if (source == null) {
            return;
        }
        try {
            BufferedImage input = ImageIO.read(new ByteArrayInputStream(source));
            if (input == null) {
                return;
            }
            BufferedImage output = new BufferedImage(input.getWidth(), input.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < input.getHeight(); y++) {
                for (int x = 0; x < input.getWidth(); x++) {
                    int pixel = input.getRGB(x, y);
                    int alpha = Math.round(((pixel >>> 24) & 0xFF) * 0.62F);
                    int red = (pixel >>> 16) & 0xFF;
                    output.setRGB(x, y, (alpha << 24) | (red << 16));
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!ImageIO.write(output, "png", bytes)) {
                return;
            }

            String texture = PROJECTILE_MODEL.toString();
            builder.addData("assets/" + PROJECTILE_MODEL.getNamespace() + "/textures/" + PROJECTILE_MODEL.getPath() + ".png",
                bytes.toByteArray());

            JsonObject model = new JsonObject();
            model.addProperty("parent", "minecraft:item/handheld");
            JsonObject textures = new JsonObject();
            textures.addProperty("layer0", texture);
            model.add("textures", textures);
            builder.addData("assets/" + PROJECTILE_MODEL.getNamespace() + "/models/" + PROJECTILE_MODEL.getPath() + ".json",
                model.toString().getBytes(StandardCharsets.UTF_8));

            JsonObject itemModel = new JsonObject();
            itemModel.addProperty("type", "minecraft:model");
            itemModel.addProperty("model", PROJECTILE_MODEL.toString());
            JsonObject definition = new JsonObject();
            definition.add("model", itemModel);
            builder.addData("assets/" + PROJECTILE_MODEL.getNamespace() + "/items/" + PROJECTILE_MODEL.getPath() + ".json",
                definition.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Could not bake the desolate dagger projectile texture", e);
        }
    }
}
