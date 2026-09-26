package me.drex.polymerpatcher.item;

import eu.pb4.factorytools.api.util.LazyItemStack;
import eu.pb4.factorytools.api.virtualentity.ItemDisplayElementUtil;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import eu.pb4.polymer.resourcepack.extras.api.format.item.ItemAsset;
import eu.pb4.polymer.resourcepack.extras.api.format.item.model.BasicItemModel;
import eu.pb4.polymer.resourcepack.extras.api.format.item.tint.MapColorTintSource;
import eu.pb4.polymer.resourcepack.extras.api.format.model.ModelAsset;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.ManualAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.entity.render.RenderCaptureRules;
import me.drex.polymerpatcher.util.NativeClients;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Shared, bounded feedback for tools which mine a point without a real block there. */
public final class AirMiningFeedback {
    public record Target(Vec3 position, int stage, String nativeNamespace) {
    }

    @FunctionalInterface
    public interface Provider {
        @Nullable Target target(ServerPlayer player, ItemStack stack);
    }

    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();
    private static final Map<UUID, Overlay> ACTIVE = new ConcurrentHashMap<>();
    private static final LazyItemStack[] STAGES = new LazyItemStack[10];
    private static boolean initialized;

    private AirMiningFeedback() {
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        for (int stage = 0; stage < STAGES.length; stage++) {
            STAGES[stage] = ItemDisplayElementUtil.getModel(model(stage));
        }
        RenderCaptureRules.registerAssets(AirMiningFeedback::generateAssets);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            java.util.Set<UUID> connected = new java.util.HashSet<>();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                connected.add(player.getUUID());
                update(player);
            }
            ACTIVE.entrySet().removeIf(entry -> {
                if (connected.contains(entry.getKey())) {
                    return false;
                }
                entry.getValue().destroy();
                return true;
            });
        });
    }

    public static void register(Provider provider) {
        PROVIDERS.add(provider);
    }

    private static void update(ServerPlayer player) {
        Target target = null;
        if (player.isUsingItem()) {
            ItemStack stack = player.getUseItem();
            for (Provider provider : PROVIDERS) {
                target = provider.target(player, stack);
                if (target != null) {
                    break;
                }
            }
        }
        if (target != null && NativeClients.has(player, target.nativeNamespace())) {
            target = null;
        }

        Overlay existing = ACTIVE.get(player.getUUID());
        if (target == null) {
            if (existing != null) {
                existing.destroy();
                ACTIVE.remove(player.getUUID());
            }
            return;
        }

        // The mod already broadcasts its server-side swing to onlookers. An unmodded owning client
        // cannot infer that it should animate while using an ordinary pickaxe in the air, so send the
        // missing first-person swing directly at the native six-tick cadence.
        if (player.tickCount % 6 == 0) {
            player.swing(player.getUsedItemHand(), true);
        }

        int stage = Math.clamp(target.stage(), 0, 9);
        if (existing == null || !existing.matches((ServerLevel) player.level(), target.position())) {
            if (existing != null) {
                existing.destroy();
            }
            existing = new Overlay(player, target.position(), stage);
            ACTIVE.put(player.getUUID(), existing);
        }
        existing.setStage(stage);
        existing.tick();
    }

    private static void generateAssets(ResourcePackBuilder builder) {
        for (int stage = 0; stage < 10; stage++) {
            Identifier model = model(stage);
            ModelAsset asset = ModelAsset.builder()
                .texture("crack", Identifier.withDefaultNamespace("block/destroy_stage_" + stage))
                .textureReference("particle", "crack")
                .element(new Vec3(0, 0, 0), new Vec3(16, 16, 16), element -> {
                    for (Direction direction : Direction.values()) {
                        element.face(direction, "#crack");
                    }
                }).build();
            builder.addData("assets/" + model.getNamespace() + "/models/" + model.getPath() + ".json", asset);
            builder.addData("assets/" + model.getNamespace() + "/items/-/" + model.getPath() + ".json",
                new ItemAsset(new BasicItemModel(model, List.of(new MapColorTintSource(0xFFFFFF))),
                    ItemAsset.Properties.DEFAULT));
        }
    }

    private static Identifier model(int stage) {
        return PolymerPatcher.id("air_mining/destroy_stage_" + stage);
    }

    private static final class Overlay {
        private final ServerLevel level;
        private final Vec3 position;
        private final ElementHolder holder = new ElementHolder();
        private final ManualAttachment attachment;
        private final ItemDisplayElement element;
        private int stage = -1;

        private Overlay(ServerPlayer player, Vec3 position, int stage) {
            this.level = (ServerLevel) player.level();
            this.position = position;
            this.element = ItemDisplayElementUtil.createSimple();
            this.element.setDisplaySize(1.5F, 1.5F);
            this.element.setScale(new Vector3f(1.01F));
            holder.addElement(element);
            setStage(stage);
            this.attachment = new ManualAttachment(holder, level, () -> position);
            this.attachment.startWatching(player);
        }

        private boolean matches(ServerLevel level, Vec3 position) {
            return this.level == level && this.position.distanceToSqr(position) < 1.0E-6;
        }

        private void setStage(int stage) {
            if (this.stage == stage) {
                return;
            }
            this.stage = stage;
            element.setItem(STAGES[stage].get());
            element.tick();
        }

        private void tick() {
            attachment.tick();
        }

        private void destroy() {
            holder.destroy();
        }
    }
}
