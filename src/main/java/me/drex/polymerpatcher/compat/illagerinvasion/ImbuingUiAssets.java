package me.drex.polymerpatcher.compat.illagerinvasion;

import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.resources.ResourceHelper;
import me.drex.polymerpatcher.util.MenuUiArtwork;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.resources.IoSupplier;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;

/** Reuses Illager Invasion's real artwork, including its conditional red error marker. */
final class ImbuingUiAssets {
    private static final String SOURCE = "textures/gui/container/imbuing_table.png";
    private static final String NORMAL = "illagerinvasion/imbuing_table";
    private static final String ERROR = "illagerinvasion/imbuing_table_error";
    private static volatile boolean generated;

    private ImbuingUiAssets() {
    }

    static void generate(ResourcePackBuilder builder) {
        generated = false;
        IoSupplier<InputStream> supplier = ResourceHelper.getAsset("illagerinvasion", SOURCE);
        if (supplier == null) {
            return;
        }
        try (InputStream stream = supplier.get()) {
            BufferedImage source = ImageIO.read(stream);
            if (source == null || source.getWidth() < 204 || source.getHeight() < 166) {
                return;
            }
            MenuUiArtwork.write(builder, NORMAL, compose(source, false));
            MenuUiArtwork.write(builder, ERROR, compose(source, true));
            generated = true;
            PolymerPatcher.LOGGER.info("Prepared vanilla Imbuing Table UI artwork from {}", SOURCE);
        } catch (Throwable throwable) {
            PolymerPatcher.LOGGER.warn("Could not prepare the vanilla Imbuing Table UI artwork", throwable);
        }
    }

    private static BufferedImage compose(BufferedImage source, boolean error) {
        BufferedImage screen = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = screen.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(source, 0, 0, 176, 166, 0, 0, 176, 166, null);
            if (error) {
                // ImbuingScreen draws this sprite at 74,32 whenever its state has a tooltip.
                graphics.drawImage(source, 74, 32, 102, 52, 176, 0, 204, 20, null);
            }
        } finally {
            graphics.dispose();
        }
        return screen;
    }

    static Component title(Component original, boolean error) {
        if (!generated) {
            return original;
        }
        return MenuUiArtwork.title(error ? ERROR : NORMAL, original);
    }
}
