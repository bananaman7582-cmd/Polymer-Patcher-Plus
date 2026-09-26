package me.drex.polymerpatcher.compat.alexscaves;

import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shader-free approximations for Alex's Caves slime shells. */
final class AlexsCavesSlimes {
    private static final Identifier FERROUS = Identifier.fromNamespaceAndPath("alexscaves", "ferrouslime");
    private static final Map<Identifier, Source> TEXTURES = new LinkedHashMap<>();

    private record Source(Identifier texture) {
    }

    private AlexsCavesSlimes() {
    }

    static @Nullable Identifier registerTexture(String entityId, Identifier texture) {
        if (!entityId.equals(FERROUS.toString())) {
            return null;
        }

        Identifier source;
        if (texture.getPath().endsWith("_gel")) {
            source = texture;
        } else if (texture.getPath().equals("entity/ferrouslime")) {
            source = Identifier.fromNamespaceAndPath("alexscaves", "entity/ferrouslime_gel");
        } else {
            return null;
        }
        Identifier out = derived(source);
        TEXTURES.putIfAbsent(out, new Source(source));
        return out;
    }

    static Identifier textureFor(Entity entity, Identifier texture) {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        Identifier derived = id != null && id.equals(FERROUS) && texture.getPath().endsWith("_gel")
            ? registerTexture(id.toString(), texture) : null;
        return derived != null ? derived : texture;
    }

    /** The gel shader is not a Citadel model draw, so capture cannot observe it; repeat the model once. */
    static @Nullable Identifier extraModelTexture(Entity entity, Identifier texture) {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (!FERROUS.equals(id) || !texture.getPath().equals("entity/ferrouslime")) {
            return null;
        }
        return registerTexture(id.toString(), texture);
    }

    static void generateTextures(ResourcePackBuilder builder) {
        for (Map.Entry<Identifier, Source> entry : TEXTURES.entrySet()) {
            byte[] encoded = builder.getDataOrSource(path(entry.getValue().texture()));
            byte[] converted = encoded == null ? null : dither(encoded, 0.68F);
            if (converted != null) {
                builder.addData(path(entry.getKey()), converted);
            }
        }
    }

    /**
     * Item models do not reproduce Citadel's translucent entity shaders reliably. Convert partial
     * alpha to stable 4x4 coverage instead: the gel remains see-through even on a cutout renderer,
     * so the ferrouslime body remains visible below it. Caramel uses its original translucent outside
     * texture; dithering that pale texture was what produced the reported white/orange glass cube.
     */
    private static @Nullable byte[] dither(byte[] encoded, float opacity) {
        final int[][] threshold = {
            {0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}
        };
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(encoded));
            if (source == null) {
                return null;
            }
            BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < source.getHeight(); y++) {
                for (int x = 0; x < source.getWidth(); x++) {
                    int pixel = source.getRGB(x, y);
                    float coverage = ((pixel >>> 24) / 255.0F) * opacity;
                    int alpha = coverage * 16.0F > threshold[y & 3][x & 3] ? 255 : 0;
                    out.setRGB(x, y, alpha << 24 | pixel & 0x00ffffff);
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            return ImageIO.write(out, "png", bytes) ? bytes.toByteArray() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Identifier derived(Identifier texture) {
        return Identifier.fromNamespaceAndPath(texture.getNamespace(),
            "entity/polymer_patcher_slime_shell/" + texture.getPath().replaceFirst("^entity/", ""));
    }

    private static String path(Identifier texture) {
        return "assets/" + texture.getNamespace() + "/textures/" + texture.getPath() + ".png";
    }
}
