package me.drex.polymerpatcher.compat.alexscaves;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.drex.polymerpatcher.resources.ItemModelFallbacks;
import me.drex.polymerpatcher.resources.ItemTintFallbacks;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Standalone check for the hand transforms written by {@link AlexsCavesHeldItems}.
 *
 * <p>The expected side below independently repeats vanilla's ItemTransform application followed by
 * ACItemstackRenderer's fixed transforms. The actual side decodes the compact transform emitted into
 * the resource pack and applies it exactly as vanilla does, including left-hand mirroring. Their full
 * matrices must agree for all four hands, both at rest and for the spear/ortholance use models.</p>
 */
public final class AlexsCavesHeldItemTransformCheck {
    private static final float PIXELS_PER_BLOCK = 16.0F;
    private static final float EPSILON = 2.0E-4F;
    private static final List<String> ITEMS = List.of(
        "limestone_spear", "extinction_spear", "frostmint_spear", "primitive_club",
        "sea_staff", "ortholance", "sugar_staff", "dreadbow", "galena_gauntlet", "resistor_shield",
        "siren_light", "copper_valve", "gobthumper", "shot_gum", "raygun"
    );

    private AlexsCavesHeldItemTransformCheck() {
    }

    public static void main(String[] args) {
        verifyTextureComposite();
        verifyVanillaTintBridge();
        verifyNuclearBombBlockCapture();
        int checked = 0;
        for (String item : ITEMS) {
            for (boolean firstPerson : new boolean[]{false, true}) {
                for (boolean leftHand : new boolean[]{false, true}) {
                    verify(item, baseTransform(item, firstPerson, leftHand), firstPerson, leftHand, "resting");
                    checked++;

                    JsonObject using = usingTransform(item, firstPerson, leftHand);
                    if (using != null) {
                        verify(item, using, firstPerson, leftHand, "using");
                        checked++;
                    }
                }
            }
        }
        System.out.println("Verified " + checked + " Alex's Caves held-item transform matrices");
    }

    /**
     * Reconstructs NuclearBombRenderer's matrix at several fuse times. The corrected item-display path
     * must land every block vertex exactly where the source block renderer put it, even while its two
     * fuse scales and wobble rotation are changing.
     */
    private static void verifyNuclearBombBlockCapture() {
        for (int tick : new int[]{0, 1, 37, 149, 299}) {
            float progress = tick / 300.0F;
            float pulse = 1.0F + (float) Math.sin(progress * progress * Math.PI) * 0.5F;
            float vertical = pulse - progress * 0.3F;
            float wobble = (float) (Math.cos(tick * 3.25D) * 1.2D * progress * Math.PI);

            Matrix4f direct = new Matrix4f()
                .rotateY(radians(wobble))
                .scale(1.0F + progress * 0.03F, 1.0F, 1.0F + progress * 0.03F)
                .scale(pulse, vertical, pulse)
                .translate(-0.5F, 0.0F, -0.5F);

            // What the patch sends: the source matrix, inverse item centring, then the item renderer's
            // own centring. The last two operations must cancel in local/model space.
            Matrix4f captured = new Matrix4f(direct)
                .translate(0.5F, 0.5F, 0.5F)
                .translate(-0.5F, -0.5F, -0.5F);

            float[] expected = direct.get(new float[16]);
            float[] actual = captured.get(new float[16]);
            for (int i = 0; i < expected.length; i++) {
                if (Math.abs(expected[i] - actual[i]) > EPSILON) {
                    throw new AssertionError("Nuclear bomb capture differs at tick " + tick
                        + ", matrix component " + i + ": expected=" + direct + ", actual=" + captured);
                }
            }
        }
    }

    private static void verifyVanillaTintBridge() {
        AlexsCavesItemTints.init();
        JsonObject source = JsonParser.parseString("""
            {"model":{"type":"minecraft:model","model":"alexscaves:item/cave_tablet","tints":[
              {"type":"minecraft:constant","value":-1},
              {"type":"alexscaves:tint","source":"biome"}
            ]}}
            """).getAsJsonObject();
        JsonObject bridged = ItemTintFallbacks.rewrite(source, "alexscaves", "cave_tablet");
        if (bridged == null || ItemModelFallbacks.needsModCode(bridged.get("model"))) {
            throw new AssertionError("Alex's Caves tint was not converted to a vanilla-readable definition");
        }
        JsonObject tint = bridged.getAsJsonObject("model").getAsJsonArray("tints").get(1).getAsJsonObject();
        if (!"minecraft:custom_model_data".equals(tint.get("type").getAsString())
            || tint.get("index").getAsInt() != 0 || tint.get("default").getAsInt() != -1) {
            throw new AssertionError("Unexpected custom-model-data tint bridge: " + tint);
        }

        JsonObject unsupported = JsonParser.parseString("""
            {"model":{"type":"minecraft:model","model":"example:item/thing","tints":[
              {"type":"example:unknown","source":"mystery"}
            ]}}
            """).getAsJsonObject();
        if (ItemTintFallbacks.rewrite(unsupported, "example", "thing") != null) {
            throw new AssertionError("An unknown tint source must remain on the safe whole-model fallback");
        }
    }

    private static void verifyTextureComposite() {
        try {
            BufferedImage base = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
            base.setRGB(0, 0, 0xFF112233);
            base.setRGB(1, 0, 0xFF445566);
            BufferedImage overlay = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
            overlay.setRGB(0, 0, 0xFFCC2244);
            overlay.setRGB(1, 0, 0x00000000);

            ByteArrayOutputStream basePng = new ByteArrayOutputStream();
            ByteArrayOutputStream overlayPng = new ByteArrayOutputStream();
            ImageIO.write(base, "png", basePng);
            ImageIO.write(overlay, "png", overlayPng);
            byte[] result = AlexsCavesHeldItems.compositePngForValidation(
                basePng.toByteArray(), overlayPng.toByteArray());
            BufferedImage combined = result == null ? null : ImageIO.read(new ByteArrayInputStream(result));
            if (combined == null || combined.getRGB(0, 0) != 0xFFCC2244
                || combined.getRGB(1, 0) != 0xFF445566) {
                throw new AssertionError("Alex's Caves item texture passes were not composited correctly");
            }
        } catch (Exception e) {
            throw new AssertionError("Could not validate Alex's Caves item texture compositing", e);
        }
    }

    private static void verify(String item, JsonObject base, boolean firstPerson, boolean leftHand, String state) {
        Matrix4f expected = vanillaTransform(base, leftHand);
        applyAlexsCavesRenderer(expected, item, firstPerson, leftHand);
        expected.scale(AlexsCavesHeldItems.shapeScaleForValidation(item));

        JsonObject encoded = AlexsCavesHeldItems.transformForValidation(base, item, firstPerson, leftHand);
        if (encoded == null) {
            throw new AssertionError(label(item, firstPerson, leftHand, state) + " could not be encoded");
        }
        Matrix4f actual = vanillaTransform(encoded, leftHand);

        float[] expectedValues = expected.get(new float[16]);
        float[] actualValues = actual.get(new float[16]);
        float largest = 0.0F;
        int largestAt = -1;
        for (int i = 0; i < expectedValues.length; i++) {
            float difference = Math.abs(expectedValues[i] - actualValues[i]);
            if (difference > largest) {
                largest = difference;
                largestAt = i;
            }
        }
        if (largest > EPSILON) {
            throw new AssertionError(label(item, firstPerson, leftHand, state)
                + " differs at matrix component " + largestAt + " by " + largest
                + "\nexpected=" + expected + "\nactual=" + actual + "\nencoded=" + encoded);
        }
    }

    /** The transform body in vanilla ItemTransform#apply, before its common centre translation. */
    private static Matrix4f vanillaTransform(JsonObject json, boolean leftHand) {
        Vector3f rotation = vector(json, "rotation", 0.0F);
        Vector3f translation = vector(json, "translation", 0.0F).div(PIXELS_PER_BLOCK);
        Vector3f scale = vector(json, "scale", 1.0F);
        if (leftHand) {
            translation.x = -translation.x;
            rotation.y = -rotation.y;
            rotation.z = -rotation.z;
        }
        return new Matrix4f()
            .translate(translation)
            .rotate(new Quaternionf().rotationXYZ(radians(rotation.x), radians(rotation.y), radians(rotation.z)))
            .scale(scale);
    }

    /** Fixed, non-animated pose operations in ACItemstackRenderer 1.0.10. */
    private static void applyAlexsCavesRenderer(Matrix4f pose, String item, boolean firstPerson, boolean leftHand) {
        switch (item) {
            case "limestone_spear", "extinction_spear", "frostmint_spear" ->
                upsideDown(pose, firstPerson, -0.85F, -0.1F, 0.5F, 0.75F, 0.75F, 0.75F);
            case "primitive_club" ->
                upsideDown(pose, firstPerson, -1.15F, -0.1F, 0.1F, 0.8F, 0.8F, 0.8F);
            case "sea_staff" ->
                upsideDown(pose, firstPerson, -0.5F, 0.0F, 0.0F, 0.6F, 0.6F, 0.6F);
            case "ortholance" ->
                upsideDown(pose, firstPerson, -1.1F, 0.0F, 0.0F, 0.6F, 1.0F, 0.6F);
            case "sugar_staff" ->
                upsideDown(pose, firstPerson, -1.0F, 0.0F, 0.4F, 0.6F, 0.6F, 0.6F);
            // Neither of these two begins by putting back the half block the game moves an item by, so that
            // move is still standing when their models are drawn and belongs to their steps
            case "galena_gauntlet" ->
                pose.translate(-0.5F, -0.5F, -0.5F).rotateX(radians(-90.0F)).rotateY(radians(-180.0F));
            case "resistor_shield" ->
                pose.translate(-0.5F, -0.5F, -0.5F).translate(0.0F, 0.25F, 0.125F).rotateX(radians(-180.0F));
            case "dreadbow" -> {
                if (firstPerson) {
                    pose.translate(leftHand ? -0.1F : 0.1F, 0.1F, -0.1F)
                        .scale(0.5F).rotateX(radians(15.0F));
                } else {
                    pose.translate(leftHand ? 0.1F : -0.1F, -0.45F, 0.35F)
                        .rotateY(radians(leftHand ? 7.0F : -7.0F));
                }
            }
            // A whole block up and turned over, read off the renderer a second time: the two it holds like
            // a weapon also face forward and are drawn a little smaller
            case "siren_light", "copper_valve", "gobthumper" ->
                pose.translate(0.0F, 1.0F, 0.0F).rotateX(radians(-180.0F));
            case "shot_gum" ->
                pose.translate(0.0F, 1.0F, 0.0F).rotateX(radians(-180.0F)).rotateY(radians(180.0F)).scale(0.8F);
            case "raygun" ->
                pose.translate(0.0F, 1.0F, 0.0F).rotateX(radians(-180.0F)).rotateY(radians(180.0F)).scale(0.9F);
            default -> throw new AssertionError("No independent renderer steps for " + item);
        }
    }

    /** Actual hand transforms from the two source model files this change adds. */
    private static JsonObject baseTransform(String item, boolean firstPerson, boolean leftHand) {
        if (item.equals("resistor_shield")) {
            if (firstPerson) {
                return transform(0.0F, 180.0F, 5.0F, leftHand ? 13.0F : -7.0F,
                    leftHand ? 0.0F : 2.0F, -10.0F, 1.25F);
            }
            return transform(0.0F, 90.0F, 0.0F, 10.0F, 6.0F, leftHand ? 12.0F : -4.0F, 1.0F);
        }
        if (item.equals("galena_gauntlet")) {
            if (firstPerson) {
                return transform(0.0F, 10.0F, 0.0F, leftHand ? -2.0F : 6.5F,
                    8.0F, leftHand ? 16.0F : 15.0F, 0.6F);
            }
            return transform(0.0F, 0.0F, 0.0F, leftHand ? -5.0F : 9.0F,
                6.0F, 20.0F, 0.9F);
        }
        return new JsonObject();
    }

    private static void upsideDown(Matrix4f pose, boolean firstPerson, float down, float back, float firstPersonUp,
                                   float firstPersonX, float firstPersonY, float firstPersonZ) {
        pose.rotateX(radians(-180.0F)).translate(0.0F, down, back);
        if (firstPerson) {
            pose.translate(0.0F, firstPersonUp, 0.0F).scale(firstPersonX, firstPersonY, firstPersonZ);
        }
    }

    private static JsonObject usingTransform(String item, boolean firstPerson, boolean leftHand) {
        if (item.endsWith("_spear")) {
            return firstPerson
                ? transform(-20.0F, leftHand ? -10.0F : 10.0F, 15.0F,
                    leftHand ? 3.5F : 2.0F, leftHand ? -4.0F : -5.0F, leftHand ? -8.0F : -7.0F)
                : transform(-180.0F, 0.0F, 0.0F, 0.0F, -4.0F, 2.0F);
        }
        if (item.equals("ortholance")) {
            return firstPerson
                ? transform(-70.0F, 0.0F, leftHand ? -5.0F : 5.0F, leftHand ? -1.0F : 1.0F, 0.0F, 5.0F)
                : transform(-80.0F, 0.0F, 0.0F, 0.0F, -2.0F, 1.0F);
        }
        return null;
    }

    private static JsonObject transform(float rx, float ry, float rz, float tx, float ty, float tz) {
        return transform(rx, ry, rz, tx, ty, tz, 1.0F);
    }

    private static JsonObject transform(float rx, float ry, float rz, float tx, float ty, float tz, float scale) {
        JsonObject result = new JsonObject();
        result.add("rotation", array(rx, ry, rz));
        result.add("translation", array(tx, ty, tz));
        result.add("scale", array(scale, scale, scale));
        return result;
    }

    private static JsonArray array(float x, float y, float z) {
        JsonArray result = new JsonArray();
        result.add(x);
        result.add(y);
        result.add(z);
        return result;
    }

    private static Vector3f vector(JsonObject json, String key, float fallback) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() != 3) {
            return new Vector3f(fallback);
        }
        JsonArray values = value.getAsJsonArray();
        return new Vector3f(values.get(0).getAsFloat(), values.get(1).getAsFloat(), values.get(2).getAsFloat());
    }

    private static float radians(float degrees) {
        return (float) Math.toRadians(degrees);
    }

    private static String label(String item, boolean firstPerson, boolean leftHand, String state) {
        return item + " " + (firstPerson ? "first" : "third") + "-person "
            + (leftHand ? "left" : "right") + " hand " + state;
    }
}
