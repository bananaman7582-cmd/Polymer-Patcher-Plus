package me.drex.polymerpatcher.block.color;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.resources.ResourceHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

public class ColorMapHelper {
    private static final Identifier FOLIAGE_LOCATION = Identifier.withDefaultNamespace("textures/colormap/foliage.png");
    private static final Identifier GRASS_LOCATION = Identifier.withDefaultNamespace("textures/colormap/grass.png");

    public static void init() {
        IoSupplier<InputStream> foliageSupplier = ResourceHelper.getAsset(FOLIAGE_LOCATION.getNamespace(), FOLIAGE_LOCATION.getPath());
        try {
            int[] pixels = getPixels(foliageSupplier);
            FoliageColor.init(pixels);
        } catch (IOException e) {
            PolymerPatcher.LOGGER.error("Failed to load foliage color map {}", FOLIAGE_LOCATION, e);
        }

        IoSupplier<InputStream> grassSupplier = ResourceHelper.getAsset(GRASS_LOCATION.getNamespace(), GRASS_LOCATION.getPath());
        try {
            int[] pixels = getPixels(grassSupplier);
            GrassColor.init(pixels);
        } catch (IOException e) {
            PolymerPatcher.LOGGER.error("Failed to load grass color map {}", GRASS_LOCATION, e);
        }
    }

    public static int[] getPixels(IoSupplier<InputStream> supplier) throws IOException {
        try (InputStream inputStream = supplier.get()) {
            BufferedImage img = ImageIO.read(inputStream);
            return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        }
    }
}
