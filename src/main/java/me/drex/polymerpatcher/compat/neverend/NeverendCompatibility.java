package me.drex.polymerpatcher.compat.neverend;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.block.BlockPresentationRules;
import me.drex.polymerpatcher.dump.ClientOnlyClasses;
import me.drex.polymerpatcher.dump.data.RenderRegistry;
import me.drex.polymerpatcher.entity.render.RenderCaptureRules;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Server-side presentation bridges for Neverend (whose historical namespace is liminalpools). */
public final class NeverendCompatibility {
    public static final String MOD_ID = "liminalpools";

    private NeverendCompatibility() {
    }

    public static void init() {
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
            return;
        }

        // The source block is intentionally invisible because its client block-entity renderer uses
        // the vanilla end-portal renderer. Preserve that semantic surface instead of mapping it to air.
        BlockPresentationRules.registerCarrier(state ->
            id("liminal_portal").equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()))
                ? Blocks.END_PORTAL.defaultBlockState() : null);
        BlockPresentationRules.registerHolder((level, pos, state) -> {
            Identifier block = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            return id("octant").equals(block) || id("sonar").equals(block)
                ? new NeverendFloatingTextModel(level, pos, block) : null;
        });
        BlockPresentationRules.registerTicking(state -> {
            Identifier block = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            return id("octant").equals(block) || id("sonar").equals(block);
        });
        RenderCaptureRules.registerAssets(NeverendCompatibility::generateWindowFallbacks);
        // The native deep Grandgousier renderer deliberately forces full block light in the
        // Poolrooms. It only naturally exists there, so preserve that renderer semantic for its
        // display-model replacement as well.
        RenderCaptureRules.registerBrightness(entity ->
            id("deep_grandgousier").equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()))
                ? new Brightness(15, 15) : null);
        NeverendClientEffects.init();
        NeverendDivingSuit.init();

        PolymerPatcher.LOGGER.info("Enabled built-in Neverend compatibility");
    }

    /** Supplies stable vanilla-style renderers even when the current client render dump predates Neverend. */
    public static void setupRendering(RenderRegistry registry) {
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
            return;
        }

        addRenderer(registry, "poolfish",
            "doctor4t.liminalpools.client.render.entity.PoolfishEntityRenderer",
            "doctor4t.liminalpools.client.model.PoolfishEntityModel",
            "poolfish", "entity/poolfish");
        addRenderer(registry, "grandgousier",
            "doctor4t.liminalpools.client.render.entity.GrandgousierEntityRenderer",
            "doctor4t.liminalpools.client.model.GrandgousierEntityModel",
            "grandgousier", "entity/grandgousier");
        addRenderer(registry, "deep_grandgousier",
            "doctor4t.liminalpools.client.render.entity.DeepGrandgousierEntityRenderer",
            "doctor4t.liminalpools.client.model.GrandgousierEntityModel",
            "grandgousier", "entity/deep_grandgousier");
        NeverendDivingSuit.setupRendering();
    }

    @SuppressWarnings("unchecked")
    private static void addRenderer(RenderRegistry registry, String entityPath, String rendererName,
                                    String modelName, String layerPath, String texturePath) {
        Identifier entityId = id(entityPath);
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(entityId);
        if (type == null || !entityId.equals(BuiltInRegistries.ENTITY_TYPE.getKey(type))) {
            return;
        }
        if (registry.entityData.stream().anyMatch(info -> info.type() == type)) {
            return;
        }

        try {
            Class<?> model = ClientOnlyClasses.load(modelName);
            Class<?> rendererType = ClientOnlyClasses.load(rendererName);
            if (model == null || rendererType == null) {
                throw new ClassNotFoundException(model == null ? modelName : rendererName);
            }
            Method definition = model.getDeclaredMethod("getTexturedModelData");
            LayerDefinition layer = (LayerDefinition) definition.invoke(null);
            ModelLayerLocation location = new ModelLayerLocation(id(layerPath), "main");
            registry.modelLayers.putIfAbsent(location, layer);

            Class<? extends EntityRenderer> renderer =
                (Class<? extends EntityRenderer>) rendererType.asSubclass(EntityRenderer.class);
            registry.entityData.add(new RenderRegistry.RenderInfo(type, renderer, Set.of(location),
                Set.of(id(texturePath))));
            registry.namespaces.add(MOD_ID);
        } catch (Throwable throwable) {
            PolymerPatcher.LOGGER.warn("Could not prepare Neverend renderer {}", entityId, throwable);
        }
    }

    static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    /**
     * Replaces Special Models shader entries with a thin, correctly oriented emissive pane.
     * A vanilla client cannot execute Neverend's skybox shader. Using one 900x900 cubemap face on
     * every block made its sun and clouds repeat in an obvious checkerboard, so the fallback keeps
     * only each sky's representative colour. The one-pixel texture is deliberately featureless:
     * adjacent windows become one uninterrupted field instead of advertising their block borders.
     */
    private static void generateWindowFallbacks(ResourcePackBuilder builder) {
        writeWindow(builder, "abyss", 0x02001E);
        writeWindow(builder, "noon", 0x75AAD2);
        writeWindow(builder, "dusk", 0xB4A487);
    }

    private static void writeWindow(ResourcePackBuilder builder, String sky, int rgb) {
        builder.addData("assets/liminalpools/textures/block/solid_sky_" + sky + ".png", solidPng(rgb));
        builder.addData("assets/liminalpools/models/block/liminal_window_" + sky + ".json",
            windowModelJson(sky).getBytes(StandardCharsets.UTF_8));
    }

    static String windowModelJson(String sky) {
        return """
            {
              "ambientocclusion": false,
              "parent": "minecraft:block/block",
              "textures": { "sky": "liminalpools:block/solid_sky_%s", "particle": "liminalpools:block/liminal_tiles" },
              "elements": [
                {
                  "from": [0, 0, 0], "to": [16, 0.02, 16],
                  "shade": false,
                  "light_emission": 15,
                  "faces": {
                    "down": { "texture": "#sky" }, "up": { "texture": "#sky" },
                    "north": { "texture": "#sky" }, "south": { "texture": "#sky" },
                    "west": { "texture": "#sky" }, "east": { "texture": "#sky" }
                  }
                }
              ]
            }
            """.formatted(sky);
    }

    static byte[] solidPng(int rgb) {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFF000000 | rgb);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new IllegalStateException("The Java runtime has no PNG writer");
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not make Neverend's solid sky fallback", exception);
        }
    }
}
