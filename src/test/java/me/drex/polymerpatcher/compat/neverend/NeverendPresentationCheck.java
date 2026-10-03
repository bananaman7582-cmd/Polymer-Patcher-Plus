package me.drex.polymerpatcher.compat.neverend;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;

/** Regression checks for the vanilla-client Neverend sky-window fallback. */
public final class NeverendPresentationCheck {
    private NeverendPresentationCheck() {
    }

    public static void main(String[] args) throws Exception {
        String noon = NeverendCompatibility.windowModelJson("noon");
        expect(noon.contains("liminalpools:block/solid_sky_noon"),
            "the window must use its featureless solid texture");
        expect(!noon.contains("liminalpools:sky/noon_0"),
            "a cubemap face must not be tiled once per block");
        expect(noon.contains("\"light_emission\": 15"),
            "the fallback pane must remain fully bright");
        expect(noon.contains("\"shade\": false"),
            "directional block shading must not darken the solid sky");
        expect(noon.contains("\"ambientocclusion\": false"),
            "neighbouring blocks must not shade the solid sky");

        int wanted = 0x75AAD2;
        var image = ImageIO.read(new ByteArrayInputStream(NeverendCompatibility.solidPng(wanted)));
        expect(image != null && image.getWidth() == 1 && image.getHeight() == 1,
            "the generated sky texture must be one solid texel");
        expect((image.getRGB(0, 0) & 0xFFFFFF) == wanted,
            "the generated sky texture must preserve its configured colour");

        System.out.println("Verified Neverend solid emissive sky-window fallbacks");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("Neverend presentation check failed: " + message);
        }
    }
}
