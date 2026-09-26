package me.drex.polymerpatcher.compat.alexscaves;

import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Vanilla-readable stand-ins for Alex's Caves' red-ghost shader. */
public final class AlexsCavesGhosts {

    private AlexsCavesGhosts() {
    }

    /** Ghost sprite -> ordinary sprite it was derived from. */
    private static final Map<Identifier, Identifier> TEXTURES = new LinkedHashMap<>();
    private static final int FADE_STAGES = 8;

    /**
     * Files a second copy of a model beneath a texture carrying the shader's visible result.
     * The path remains below {@code entity/}, so the normal generated-model bridge also writes
     * item definitions for every part.
     */
    public static Identifier registerTexture(Identifier texture) {
        Identifier sprite = me.drex.polymerpatcher.entity.AnimatedEntities.spriteForm(texture);
        String path = sprite.getPath();
        String tail = path.startsWith("entity/") ? path.substring("entity/".length()) : path;
        Identifier ghost = Identifier.fromNamespaceAndPath(sprite.getNamespace(),
            "entity/polymer_patcher_red_ghost/" + tail);
        TEXTURES.putIfAbsent(ghost, sprite);
        return ghost;
    }

    /** Registers one of the same ghost image's alpha stages for animation on vanilla clients. */
    public static Identifier registerTexture(Identifier texture, int stage) {
        Identifier full = texture.getPath().contains("polymer_patcher_red_ghost/")
            ? texture : registerTexture(texture);
        int clamped = Math.clamp(stage, 0, FADE_STAGES);
        Identifier faded = Identifier.fromNamespaceAndPath(full.getNamespace(),
            full.getPath() + "_fade_" + clamped);
        TEXTURES.putIfAbsent(faded, animatedSource(full));
        return faded;
    }

    /** Selects the nearest pre-baked alpha stage from the entity's own fade timer. */
    public static Identifier textureForEntity(Entity entity, Identifier texture) {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (id == null || !id.equals(Identifier.fromNamespaceAndPath("alexscaves", "dinosaur_spirit"))
            || !texture.getPath().contains("/polymer_patcher_red_ghost/")) {
            return texture;
        }
        float fade = 1.0F;
        try {
            fade = ((Number) entity.getClass().getMethod("getFadeIn", float.class)
                .invoke(entity, 0.0F)).floatValue();
        } catch (Throwable ignored) {
        }
        int stage = Math.clamp(Math.round(fade * FADE_STAGES), 0, FADE_STAGES);
        return registerTexture(texture, stage);
    }

    /** Returns the ghost copy only for the render layer Alex's Caves uses for dinosaur spirits. */
    public static Identifier textureFor(@Nullable RenderType renderType, Identifier ordinary) {
        return isRedGhost(renderType) ? registerTexture(ordinary) : ordinary;
    }

    private static boolean isRedGhost(@Nullable RenderType renderType) {
        return renderType != null && renderType.toString().toLowerCase(java.util.Locale.ROOT).contains("red_ghost");
    }

    /** Writes the coloured, translucent copies requested while entity models were discovered. */
    public static void generateTextures(ResourcePackBuilder builder) {
        for (var entry : TEXTURES.entrySet()) {
            Identifier ghost = entry.getKey();
            Identifier source = entry.getValue();
            byte[] original = builder.getDataOrSource(texturePath(source));
            int stage = fadeStage(ghost);
            byte[] converted = original == null ? null : redGhost(original,
                stage < 0 ? 1.0F : stage / (float) FADE_STAGES);
            if (converted != null) {
                builder.addData(texturePath(ghost), converted);
            }
        }
    }

    /**
     * The original shader is not available to an unmodded client. Preserve the texture's detail and
     * holes, tint its luminance toward the shader's fiery orange, and carry its fade in the PNG alpha.
     */
    private static @Nullable byte[] redGhost(byte[] encoded, float opacity) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(encoded));
            if (source == null) {
                return null;
            }

            BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < source.getHeight(); y++) {
                for (int x = 0; x < source.getWidth(); x++) {
                    int pixel = source.getRGB(x, y);
                    int alpha = pixel >>> 24;
                    int red = pixel >>> 16 & 0xff;
                    int green = pixel >>> 8 & 0xff;
                    int blue = pixel & 0xff;
                    float luminance = (red * 0.299F + green * 0.587F + blue * 0.114F) / 255.0F;
                    float light = 0.38F + luminance * 0.62F;
                    int ghostRed = Math.clamp(Math.round(255.0F * light), 0, 255);
                    int ghostGreen = Math.clamp(Math.round(112.0F * light), 0, 255);
                    int ghostBlue = Math.clamp(Math.round(28.0F * light), 0, 255);
                    int ghostAlpha = Math.clamp(Math.round(alpha * 0.58F * opacity), 0, 255);
                    out.setRGB(x, y, ghostAlpha << 24 | ghostRed << 16 | ghostGreen << 8 | ghostBlue);
                }
            }

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            return ImageIO.write(out, "png", bytes) ? bytes.toByteArray() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int fadeStage(Identifier texture) {
        String path = texture.getPath();
        int marker = path.lastIndexOf("_fade_");
        if (marker < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(path.substring(marker + 6));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /** Variant entries still point at the ordinary sprite, not at another generated texture. */
    private static Identifier animatedSource(Identifier full) {
        Identifier source = TEXTURES.get(full);
        return source == null ? full : source;
    }

    private static String texturePath(Identifier texture) {
        return "assets/" + texture.getNamespace() + "/textures/" + texture.getPath() + ".png";
    }
}
