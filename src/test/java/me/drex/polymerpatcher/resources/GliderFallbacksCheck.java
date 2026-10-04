package me.drex.polymerpatcher.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Executable regression check for vanilla Elytra asset and UV conversion. */
public final class GliderFallbacksCheck {
    private GliderFallbacksCheck() {
    }

    public static void main(String[] args) throws Exception {
        JsonObject layer = new JsonObject();
        layer.addProperty("texture", "example:wing");
        JsonObject equipment = JsonParser.parseString(new String(
            GliderFallbacks.wingsEquipmentJson(layer), StandardCharsets.UTF_8)).getAsJsonObject();
        if (!equipment.getAsJsonObject("layers").has("wings")
            || equipment.getAsJsonObject("layers").has("humanoid")) {
            throw new AssertionError("Generated glider equipment is not wings-only: " + equipment);
        }

        BufferedImage custom = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        custom.setRGB(34, 35, 0xFFFF3366);
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            ImageIO.write(custom, "png", bytes);
            BufferedImage converted = ImageIO.read(new ByteArrayInputStream(
                GliderFallbacks.toVanillaWingTexture(bytes.toByteArray())));
            if (converted.getWidth() != 64 || converted.getHeight() != 32) {
                throw new AssertionError("Expected a 64x32 vanilla Elytra texture, got "
                    + converted.getWidth() + "x" + converted.getHeight());
            }
            // ModelAMElytra's UV origin 32,32 becomes vanilla Elytra's 22,0.
            if (converted.getRGB(24, 3) != 0xFFFF3366) {
                throw new AssertionError("Custom wing pixel was not translated to vanilla Elytra UVs");
            }
        }
        System.out.println("Verified wings-only equipment JSON and custom-to-vanilla Elytra UV conversion");
    }
}
