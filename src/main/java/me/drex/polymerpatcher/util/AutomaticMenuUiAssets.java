package me.drex.polymerpatcher.util;

import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.resources.ResourceHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.IoSupplier;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Discovers ordinary 176-pixel mod menu artwork and exposes it as an unclickable title glyph. */
final class AutomaticMenuUiAssets {
    private static final Set<String> GENERATED = ConcurrentHashMap.newKeySet();

    private AutomaticMenuUiAssets() {
    }

    static void generate(ResourcePackBuilder builder) {
        GENERATED.clear();
        for (Identifier menuId : BuiltInRegistries.MENU.keySet()) {
            if (menuId.getNamespace().equals("minecraft")) {
                continue;
            }
            Source source = findSource(menuId);
            if (source == null) {
                continue;
            }
            for (int rows = 1; rows <= 6; rows++) {
                try {
                    write(builder, menuId, rows, source.image());
                    GENERATED.add(key(menuId, rows));
                } catch (Throwable throwable) {
                    PolymerPatcher.LOGGER.warn("Could not make automatic {}-row UI artwork for {} from {}",
                        rows, menuId, source.path(), throwable);
                    break;
                }
            }
            PolymerPatcher.LOGGER.info("Prepared automatic vanilla UI artwork for {} from {}",
                menuId, source.path());
        }
    }

    private static Source findSource(Identifier menuId) {
        for (String path : candidates(menuId.getPath())) {
            IoSupplier<InputStream> supplier = ResourceHelper.getAsset(menuId.getNamespace(), path);
            if (supplier == null) {
                continue;
            }
            try (InputStream stream = supplier.get()) {
                BufferedImage image = ImageIO.read(stream);
                if (image != null && image.getWidth() >= 176 && image.getHeight() >= 100) {
                    return new Source(path, image);
                }
            } catch (Throwable throwable) {
                PolymerPatcher.LOGGER.debug("Could not read possible menu artwork {}:{}",
                    menuId.getNamespace(), path, throwable);
            }
        }
        return null;
    }

    static List<String> candidates(String registryPath) {
        Set<String> bases = new LinkedHashSet<>();
        bases.add(registryPath);
        for (String suffix : List.of("_menu", "_screen", "_container")) {
            if (registryPath.endsWith(suffix)) {
                bases.add(registryPath.substring(0, registryPath.length() - suffix.length()));
            }
        }
        List<String> result = new ArrayList<>();
        for (String base : bases) {
            result.add("textures/gui/" + base + ".png");
            result.add("textures/gui/container/" + base + ".png");
            result.add("textures/gui/inventory/" + base + ".png");
            result.add("textures/gui/" + base + "_gui.png");
            result.add("textures/gui/container/" + base + "_gui.png");
        }
        return List.copyOf(result);
    }

    private static void write(ResourcePackBuilder builder, Identifier menuId, int rows,
                              BufferedImage source) throws Exception {
        int visibleHeight = 114 + rows * 18;
        BufferedImage screen = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = screen.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            int copiedHeight = Math.min(visibleHeight, source.getHeight());
            graphics.drawImage(source, 0, 0, 176, copiedHeight, 0, 0, 176, copiedHeight, null);
        } finally {
            graphics.dispose();
        }

        String asset = asset(menuId, rows);
        MenuUiArtwork.write(builder, "automatic/" + asset, screen);
    }

    static Component title(Identifier menuId, int rows, Component original) {
        if (!GENERATED.contains(key(menuId, rows))) {
            return original;
        }
        return MenuUiArtwork.title("automatic/" + asset(menuId, rows), original);
    }

    private static String key(Identifier menuId, int rows) {
        return menuId + "#" + rows;
    }

    private static String asset(Identifier menuId, int rows) {
        return menuId.getNamespace() + "/" + menuId.getPath() + "_" + rows;
    }

    private record Source(String path, BufferedImage image) {
    }
}
