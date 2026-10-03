package me.drex.polymerpatcher.compat.neverend;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.properties.Property;
import eu.pb4.polymer.virtualentity.api.attachment.EntityAttachment;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.entity.AnimatedEntities;
import me.drex.polymerpatcher.entity.PolyModelInstance;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Server-side presentation of Neverend's attachment-backed diving suit. */
public final class NeverendDivingSuit {
    private static final Identifier WIDE_TEXTURE = NeverendCompatibility.id("entity/diving_suit");
    private static final Identifier SLIM_TEXTURE = NeverendCompatibility.id("entity/diving_suit_slim");
    private static final ModelLayerLocation WIDE_LAYER =
        new ModelLayerLocation(NeverendCompatibility.id("diving_suit_layer"), "main");
    private static final ModelLayerLocation SLIM_LAYER =
        new ModelLayerLocation(NeverendCompatibility.id("diving_suit_slim_layer"), "main");
    private static final Map<UUID, Attached> ATTACHED = new HashMap<>();

    private static Method hasDivingSuit;
    private static SuitDefinition wide;
    private static SuitDefinition slim;

    private record SuitDefinition(PlayerModel model, ModelPart root,
                                  ModelLayerLocation layer, Identifier texture) {
    }

    private record Attached(NeverendDivingSuitModel holder, EntityAttachment attachment,
                            ServerPlayer player, boolean slim) {
    }

    private NeverendDivingSuit() {
    }

    public static void init() {
        try {
            Class<?> components = Class.forName("doctor4t.liminalpools.common.component.ModComponents");
            hasDivingSuit = components.getMethod("hasDivingSuit", Entity.class);
        } catch (Throwable throwable) {
            PolymerPatcher.LOGGER.warn("Could not read Neverend's diving-suit state; its suit will not be drawn", throwable);
            return;
        }

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (wide == null || slim == null) return;

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                UUID id = player.getUUID();
                Attached attached = ATTACHED.get(id);
                boolean wanted = hasSuit(player);
                boolean isSlim = slim(player);

                if (attached != null && (!wanted || attached.player() != player || attached.slim() != isSlim)) {
                    attached.holder().destroy();
                    ATTACHED.remove(id);
                    attached = null;
                }

                if (wanted && attached == null) {
                    SuitDefinition definition = isSlim ? slim : wide;
                    NeverendDivingSuitModel holder = new NeverendDivingSuitModel(player, definition.model(),
                        definition.root(), definition.layer(), definition.texture());
                    EntityAttachment attachment = EntityAttachment.ofTicking(holder, player);
                    ATTACHED.put(id, new Attached(holder, attachment, player, isSlim));
                }
            }

            ATTACHED.entrySet().removeIf(entry -> {
                if (entry.getValue().player().hasDisconnected()) {
                    entry.getValue().holder().destroy();
                    return true;
                }
                return false;
            });
        });
    }

    /** Called after client-only Minecraft model classes have been made available on the server. */
    public static void setupRendering() {
        wide = create(false, WIDE_LAYER, WIDE_TEXTURE);
        slim = create(true, SLIM_LAYER, SLIM_TEXTURE);

        AnimatedEntities.POLY_MODELS.add(PolyModelInstance.create(null, wide.layer(),
            wide.root().getAllParts(), wide.texture()));
        AnimatedEntities.POLY_MODELS.add(PolyModelInstance.create(null, slim.layer(),
            slim.root().getAllParts(), slim.texture()));
    }

    public static boolean hasSuit(Entity entity) {
        Method method = hasDivingSuit;
        if (method == null) return false;
        try {
            return Boolean.TRUE.equals(method.invoke(null, entity));
        } catch (Throwable throwable) {
            PolymerPatcher.LOGGER.debug("Could not inspect Neverend diving-suit state", throwable);
            return false;
        }
    }

    private static SuitDefinition create(boolean narrow, ModelLayerLocation layer, Identifier texture) {
        // Neverend inflates the whole body by 0.28 and replaces the ordinary head/hat dilation with
        // 1.3/1.8. Rebuild those two children directly so the exact geometry is available even though
        // the mod's BobbleDilation class lives in its client-only source set.
        MeshDefinition mesh = PlayerModel.createMesh(new CubeDeformation(0.28F), narrow);
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 0)
            .addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F, new CubeDeformation(1.3F)), PartPose.ZERO);
        root.addOrReplaceChild("hat", CubeListBuilder.create().texOffs(32, 0)
            .addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F, new CubeDeformation(1.8F)), PartPose.ZERO);
        ModelPart baked = LayerDefinition.create(mesh, 64, 64).bakeRoot();
        return new SuitDefinition(new PlayerModel(baked, narrow), baked, layer, texture);
    }

    /** Reads the authoritative slim/wide metadata already present in the authenticated game profile. */
    private static boolean slim(ServerPlayer player) {
        try {
            for (Property property : player.getGameProfile().properties().get("textures")) {
                JsonObject root = JsonParser.parseString(new String(
                    Base64.getDecoder().decode(property.value()), StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject textures = root.getAsJsonObject("textures");
                JsonObject skin = textures == null ? null : textures.getAsJsonObject("SKIN");
                JsonObject metadata = skin == null ? null : skin.getAsJsonObject("metadata");
                if (metadata != null && metadata.has("model")) {
                    return "slim".equalsIgnoreCase(metadata.get("model").getAsString());
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
