package me.drex.polymerpatcher.compat.witherstorm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.block.BlockPresentationRules;
import me.drex.polymerpatcher.resources.ResourceHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.IoSupplier;
import org.jspecify.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Cracker's Wither Storm Mod.
 * <p>
 * The withered beacons' block renderers are drawn with displays (see {@link WitherBeaconModel}), and the
 * mod's own packets are passed on to players without it (see {@link WitherStormPackets}).
 */
public final class WitherStormCompatibility {
    private static final Identifier SUPER_BEACON = Identifier.fromNamespaceAndPath(WitherStormPackets.MOD_ID, "super_beacon");
    private static final Identifier SUPPORT_BEACON = Identifier.fromNamespaceAndPath(WitherStormPackets.MOD_ID, "super_support_beacon");

    static final Identifier JOIN_BEAM = PolymerPatcher.id("witherstorm/join_beam");
    static final Identifier SKY_BEAM = PolymerPatcher.id("witherstorm/sky_beam");
    static final Identifier SKY_BEAM_GLOW = PolymerPatcher.id("witherstorm/sky_beam_glow");

    /** Each support beacon colour, and the crystal texture its renderer picks for it. */
    private static final Map<String, String> TEXTURES = Map.of("AQUA", "diamond", "GREEN", "emerald", "GRAY", "iron", "RED", "redstone");

    private WitherStormCompatibility() {
    }

    public static void init() {
        if (!FabricLoader.getInstance().isModLoaded(WitherStormPackets.MOD_ID)) {
            return;
        }
        BlockPresentationRules.registerHolder((level, pos, state) -> {
            Identifier block = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if (SUPER_BEACON.equals(block)) {
                return new WitherBeaconModel(pos, true);
            }
            return SUPPORT_BEACON.equals(block) ? new WitherBeaconModel(pos, false) : null;
        });
        BlockPresentationRules.registerTicking(state -> {
            Identifier block = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            return SUPER_BEACON.equals(block) || SUPPORT_BEACON.equals(block);
        });
        PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(WitherStormCompatibility::writeAssets);
        PolymerPatcher.LOGGER.info("Enabled built-in Wither Storm compatibility");
    }

    static String textureFor(String color) {
        return TEXTURES.getOrDefault(color, "iron");
    }

    /** The crystal of the main beacon, or of a support beacon wearing this colour's texture. */
    static Identifier crystalModel(@Nullable String supportTexture) {
        return PolymerPatcher.id("witherstorm/crystal_" + (supportTexture == null ? "main" : supportTexture));
    }

    private static void writeAssets(ResourcePackBuilder builder) {
        try {
            // The main crystal is cut from the middle of the tainted dust texture, three pixels in from each
            // edge; a support crystal from the middle quarter of its own texture
            writeCube(builder, "crystal_main", "witherstormmod:block/tainted_dust_block", 3, 13);
            for (String texture : TEXTURES.values()) {
                writeCube(builder, "crystal_" + texture, "witherstormmod:block/support_beacon_" + texture, 6, 10);
            }

            byte[] beam = read("witherstormmod", "textures/misc/beam.png");
            if (beam != null) {
                builder.addData("assets/polymer-patcher/textures/block/witherstorm/beam.png", beam);
            }
            byte[] sky = read("minecraft", "textures/entity/beacon/beacon_beam.png");
            if (sky == null) {
                sky = read("minecraft", "textures/entity/beacon_beam.png");
            }
            if (sky != null) {
                builder.addData("assets/polymer-patcher/textures/block/witherstorm/beacon_beam.png", sky);
                // The vanilla beam's outer glow is the same picture at an eighth of the opacity
                builder.addData("assets/polymer-patcher/textures/block/witherstorm/beacon_beam_glow.png", faded(sky, 0.125F));
            }

            JsonObject join = model("polymer-patcher:block/witherstorm/beam");
            JsonArray joinElements = new JsonArray();
            joinElements.add(plane(new int[]{0, 0, 8}, new int[]{16, 16, 8}, "north", "south"));
            joinElements.add(plane(new int[]{8, 0, 0}, new int[]{8, 16, 16}, "east", "west"));
            join.add("elements", joinElements);
            writeModel(builder, "join_beam", join);

            for (String name : new String[]{"sky_beam", "sky_beam_glow"}) {
                JsonObject column = model("polymer-patcher:block/witherstorm/" + (name.equals("sky_beam") ? "beacon_beam" : "beacon_beam_glow"));
                JsonArray elements = new JsonArray();
                elements.add(plane(new int[]{0, 0, 0}, new int[]{16, 16, 16}, "north", "south", "east", "west"));
                column.add("elements", elements);
                writeModel(builder, name, column);
            }
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.warn("Could not write the withered beacons' crystals and beams into the pack", e);
        }
    }

    private static void writeCube(ResourcePackBuilder builder, String name, String texture, int from, int to) {
        JsonObject model = model(texture);
        JsonObject element = new JsonObject();
        element.add("from", array(0, 0, 0));
        element.add("to", array(16, 16, 16));
        element.addProperty("shade", false);
        JsonObject faces = new JsonObject();
        for (String side : new String[]{"north", "south", "east", "west", "up", "down"}) {
            JsonObject face = new JsonObject();
            face.add("uv", array(from, from, to, to));
            face.addProperty("texture", "#t");
            faces.add(side, face);
        }
        element.add("faces", faces);
        JsonArray elements = new JsonArray();
        elements.add(element);
        model.add("elements", elements);
        writeModel(builder, name, model);
    }

    private static JsonObject plane(int[] from, int[] to, String... sides) {
        JsonObject element = new JsonObject();
        element.add("from", array(from));
        element.add("to", array(to));
        element.addProperty("shade", false);
        JsonObject faces = new JsonObject();
        for (String side : sides) {
            JsonObject face = new JsonObject();
            face.add("uv", array(0, 0, 16, 16));
            face.addProperty("texture", "#t");
            face.addProperty("tintindex", 0);
            faces.add(side, face);
        }
        element.add("faces", faces);
        return element;
    }

    private static JsonObject model(String texture) {
        JsonObject textures = new JsonObject();
        textures.addProperty("t", texture);
        textures.addProperty("particle", texture);
        JsonObject model = new JsonObject();
        model.add("textures", textures);
        return model;
    }

    private static void writeModel(ResourcePackBuilder builder, String name, JsonObject model) {
        builder.addData("assets/polymer-patcher/models/item/witherstorm/" + name + ".json", model.toString().getBytes(StandardCharsets.UTF_8));
        JsonObject tint = new JsonObject();
        tint.addProperty("type", "minecraft:dye");
        tint.addProperty("default", -1);
        JsonArray tints = new JsonArray();
        tints.add(tint);
        JsonObject inner = new JsonObject();
        inner.addProperty("type", "minecraft:model");
        inner.addProperty("model", "polymer-patcher:item/witherstorm/" + name);
        inner.add("tints", tints);
        JsonObject definition = new JsonObject();
        definition.add("model", inner);
        builder.addData("assets/polymer-patcher/items/witherstorm/" + name + ".json", definition.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static JsonArray array(int... values) {
        JsonArray out = new JsonArray();
        for (int value : values) {
            out.add(value);
        }
        return out;
    }

    private static byte @Nullable [] read(String namespace, String path) {
        try {
            IoSupplier<InputStream> asset = ResourceHelper.getAsset(namespace, path);
            if (asset == null) {
                return null;
            }
            try (InputStream in = asset.get()) {
                return in.readAllBytes();
            }
        } catch (Throwable e) {
            return null;
        }
    }

    private static byte[] faded(byte[] png, float opacity) throws java.io.IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        BufferedImage out = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                int argb = image.getRGB(x, y);
                int alpha = Math.round(((argb >>> 24) & 0xFF) * opacity);
                out.setRGB(x, y, (alpha << 24) | (argb & 0xFFFFFF));
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(out, "png", bytes);
        return bytes.toByteArray();
    }
}
