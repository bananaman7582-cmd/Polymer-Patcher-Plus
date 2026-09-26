package me.drex.polymerpatcher.util;

import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Shared, non-interactive bitmap-font backdrop used by both explicit and automatic vanilla UIs. */
public final class MenuUiArtwork {
    private static final String LEADING_SPACE = "\uE2F0";
    private static final String BACKGROUND = "\uE2F1";
    private static final String TRAILING_SPACE = "\uE2F2";

    private MenuUiArtwork() {
    }

    public static void write(ResourcePackBuilder builder, String asset, BufferedImage image) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("No PNG writer is available");
            }
            builder.addData("assets/polymer-patcher/textures/gui/" + asset + ".png", output.toByteArray());
        }

        String font = """
            {
              "providers": [
                {
                  "type": "space",
                  "advances": {
                    "\\uE2F0": -8,
                    "\\uE2F2": -168
                  }
                },
                {
                  "type": "bitmap",
                  "file": "polymer-patcher:gui/%s.png",
                  "ascent": 13,
                  "height": 256,
                  "chars": ["\\uE2F1"]
                }
              ]
            }
            """.formatted(asset);
        builder.addData("assets/polymer-patcher/font/" + asset + ".json",
            font.getBytes(StandardCharsets.UTF_8));
    }

    public static Component title(String asset, Component original) {
        return artwork(asset).append(defaultTitle(original));
    }

    /** The backdrop without its ordinary title, for screens which layer live gauges over it. */
    public static MutableComponent artwork(String asset) {
        Style artwork = Style.EMPTY.withColor(0xFFFFFF).withoutShadow()
            .withFont(new FontDescription.Resource(PolymerPatcher.id(asset)));
        return Component.literal(LEADING_SPACE + BACKGROUND + TRAILING_SPACE).withStyle(artwork);
    }

    /** Restores Minecraft's font after a bitmap title component. */
    public static Component defaultTitle(Component original) {
        // Children inherit fonts, so explicitly return the actual name to Minecraft's default font.
        return original.copy().withStyle(Style.EMPTY.withFont(FontDescription.DEFAULT)
            .withColor(0x404040).withoutShadow());
    }
}
