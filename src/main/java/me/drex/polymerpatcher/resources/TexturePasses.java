package me.drex.polymerpatcher.resources;

import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Folds the extra passes a renderer draws over a model into the one picture a model file can wear.
 * <p>
 * A mod that wants part of a model to glow draws the whole model a second time with another texture, on a
 * render type that ignores light. A vanilla model file has no second pass and no such render type: it has
 * one texture, and whatever is not in it is not drawn. So the passes are combined into a single picture
 * before the pack is written - the glow is not emissive, but it is there, which is the difference between a
 * dreadbow with a red eye and a dreadbow with a hole where its eye should be.
 * <p>
 * Nothing here is particular to one mod. The names are the ones mods have agreed on between themselves
 * without ever writing it down: {@code _eye}, {@code _eyes}, {@code _glow}, and Alex's Caves' own
 * {@code _hex} for the sugar staff.
 */
public final class TexturePasses {

    private TexturePasses() {
    }

    /** What a second pass over the same model is usually called. */
    private static final List<String> PASSES = List.of("_eye", "_eyes", "_glow", "_hex", "_emissive");

    /**
     * The picture this shape should wear: the one given, or a new one with its extra passes folded in.
     */
    public static Identifier over(ResourcePackBuilder builder, Identifier texture) {
        byte[] base = builder.getDataOrSource(path(texture));
        if (base == null) {
            return texture;
        }

        byte[] combined = null;
        StringBuilder folded = new StringBuilder();

        for (String pass : PASSES) {
            Identifier overlayId = Identifier.fromNamespaceAndPath(texture.getNamespace(), texture.getPath() + pass);
            byte[] overlay = builder.getDataOrSource(path(overlayId));
            if (overlay == null) {
                continue;
            }

            byte[] next = composite(combined == null ? base : combined, overlay);
            if (next == null) {
                continue;
            }
            combined = next;
            folded.append(folded.isEmpty() ? "" : ", ").append(pass);
        }

        if (combined == null) {
            return texture;
        }

        Identifier id = PolymerPatcher.id("item_shape/" + texture.getNamespace() + "/"
            + texture.getPath().replace('/', '_') + "_lit");
        builder.addData(path(id), combined);
        // Nothing else in the pack mentions this picture, and one no model file asks for by name is left
        // out of the atlas, which is the same as having no picture at all
        ResourcePackGenerator.EXTRA_SPRITES.add(id);
        PolymerPatcher.LOGGER.debug("{} wears its {} pass, which its renderer would have drawn separately",
            texture, folded);
        return id;
    }

    private static String path(Identifier texture) {
        return "assets/" + texture.getNamespace() + "/textures/" + texture.getPath() + ".png";
    }

    /**
     * One picture drawn over another, keeping what shows through.
     */
    public static @Nullable byte[] composite(byte[] baseBytes, byte[] overlayBytes) {
        try {
            BufferedImage base = ImageIO.read(new ByteArrayInputStream(baseBytes));
            BufferedImage overlay = ImageIO.read(new ByteArrayInputStream(overlayBytes));
            if (base == null || overlay == null || base.getWidth() != overlay.getWidth()
                || base.getHeight() != overlay.getHeight()) {
                return null;
            }

            BufferedImage combined = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = combined.createGraphics();
            try {
                graphics.setComposite(AlphaComposite.Src);
                graphics.drawImage(base, 0, 0, null);
                graphics.setComposite(AlphaComposite.SrcOver);
                graphics.drawImage(overlay, 0, 0, null);
            } finally {
                graphics.dispose();
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            return ImageIO.write(combined, "png", output) ? output.toByteArray() : null;
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Could not combine two texture passes", e);
            return null;
        }
    }
}
