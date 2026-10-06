package me.drex.polymerpatcher.dump.generation;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.datafixers.util.Pair;
import me.drex.polymerpatcher.dump.PolymerPatcherDumper;
import me.drex.polymerpatcher.dump.data.RenderRegistry;
import me.drex.polymerpatcher.dump.data.RenderRegistry.ArmorInfo;
import me.drex.polymerpatcher.dump.data.RenderRegistry.BlockInfo;
import me.drex.polymerpatcher.dump.data.RenderRegistry.RenderInfo;
import me.drex.polymerpatcher.dump.mixin.RenderSetupAccessor;
import me.drex.polymerpatcher.dump.mixin.RenderTypeAccessor;
import me.drex.polymerpatcher.dump.render.DumpSubmitNodeCollector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.client.rendering.v1.ArmorRenderer;
import net.fabricmc.fabric.impl.client.rendering.ArmorRendererRegistryImpl;

public final class RenderRegistryGenerator {
    /**
     * Every side a model can hand back quads for, and the null that stands for the ones belonging to
     * no side at all - a model that only carries those would otherwise read as having no geometry.
     */
    private static final List<Direction> DIRECTIONS = new ArrayList<>() {{
        add(null);
        addAll(Arrays.asList(Direction.values()));
    }};

    private RenderRegistryGenerator() {
    }

    public static class RenderInfoBuilder {
        private final EntityType<?> type;
        private final Map<ModelLayerLocation, LayerDefinition> modelLayers = new HashMap<>();
        private final Set<Identifier> textures = new HashSet<>();
        private Class<? extends EntityRenderer> entityRenderer;


        public RenderInfoBuilder(EntityType<?> type) {
            this.type = type;
        }

        public void entityRenderer(Class<? extends EntityRenderer> entityRenderer) {
            this.entityRenderer = entityRenderer;
        }

        public void modelLayer(ModelLayerLocation modelLayerLocation, LayerDefinition modelLayer) {
            this.modelLayers.put(modelLayerLocation, modelLayer);
        }

        public void texture(Identifier texture) {
            if (this.textures.add(texture)) {
                PolymerPatcherDumper.LOGGER.debug("{} found for {}", texture, this.type);
            }
        }

        public Map<ModelLayerLocation, LayerDefinition> modelLayers() {
            return this.modelLayers;
        }

        public RenderInfo build() {
            return new RenderInfo(type, entityRenderer, new LinkedHashSet<>(modelLayers.keySet()), expandEntityTextures(textures));
        }
    }

    private static final Map<EntityType<?>, RenderInfoBuilder> BUILDERS_BY_TYPE = new ConcurrentHashMap<>();
    private static final ThreadLocal<RenderInfoBuilder> INFO_BUILDER = new ThreadLocal<>();

    /** The layer a baked root came from, retained by identity so renamed armor layers stay discoverable. */
    private static final Map<ModelPart, ModelLayerLocation> LAYERS_BY_ROOT =
        java.util.Collections.synchronizedMap(new IdentityHashMap<>());

    /** Roots baked directly by a renderer rather than through EntityModelSet. */
    private static final Map<ModelPart, LayerDefinition> DEFINITIONS_BY_ROOT =
        java.util.Collections.synchronizedMap(new IdentityHashMap<>());

    /** Definitions observed while baking, including ones registered lazily by an armor renderer. */
    private static final Map<ModelLayerLocation, LayerDefinition> BAKED_DEFINITIONS = new HashMap<>();

    /** The Fabric armor renderer currently being sampled, if any. */
    private static final ThreadLocal<ArmorInfoBuilder> ARMOR_BUILDER = new ThreadLocal<>();

    private static final class ArmorInfoBuilder {
        private final Item item;
        private final ModelPart contextRoot;
        private final List<ArmorInfo> entries = new ArrayList<>();
        private ModelLayerLocation pendingLayer;
        private int nextSyntheticLayer;

        private ArmorInfoBuilder(Item item, ModelPart contextRoot) {
            this.item = item;
            this.contextRoot = contextRoot;
        }

        private void model(Model<?> model) {
            ModelPart root = model.root();
            if (root != contextRoot) {
                pendingLayer = LAYERS_BY_ROOT.get(root);
                if (pendingLayer == null) {
                    LayerDefinition definition = DEFINITIONS_BY_ROOT.get(root);
                    Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
                    if (definition != null && itemId != null) {
                        pendingLayer = new ModelLayerLocation(
                            Identifier.fromNamespaceAndPath("polymer-patcher",
                                "armor/" + itemId.getNamespace() + "/" + itemId.getPath()),
                            "captured_" + nextSyntheticLayer++);
                        LAYERS_BY_ROOT.put(root, pendingLayer);
                        BAKED_DEFINITIONS.put(pendingLayer, definition);
                    }
                }
            }
        }

        private void texture(Identifier texture) {
            if (pendingLayer == null || texture == null) {
                return;
            }

            ArmorInfo info = new ArmorInfo(item, pendingLayer, texture);
            if (!entries.contains(info)) {
                entries.add(info);
            }
        }
    }

    public static <T extends EntityRenderer<?, ?>> T captureEntityRenderer(EntityType<?> entityType, Supplier<T> factory) {
        RenderInfoBuilder builder = new RenderInfoBuilder(entityType);
        return withBuilderContext(builder, builder1 -> {
            T renderer = factory.get();

            builder.entityRenderer(renderer.getClass());
            BUILDERS_BY_TYPE.put(entityType, builder);
            return renderer;
        });
    }

    public static void captureTexture(RenderType renderType) {
        RenderSetup renderSetup = ((RenderTypeAccessor) renderType).getState();
        Map<String, RenderSetup.TextureBinding> textures = ((RenderSetupAccessor) (Object) renderSetup).polymer_patcher$textures();
        inBuilderContext(builder -> {
            textures.values().forEach(texture -> {
                builder.texture(texture.location());
            });
        });

        ArmorInfoBuilder armor = ARMOR_BUILDER.get();
        if (armor != null) {
            RenderSetup.TextureBinding main = textures.get("Sampler0");
            if (main == null) {
                main = textures.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
            }
            if (main != null) {
                armor.texture(main.location());
            }
        }
    }

    public static void captureBakedModel(ModelLayerLocation layer, LayerDefinition definition, ModelPart root) {
        LAYERS_BY_ROOT.put(root, layer);
        BAKED_DEFINITIONS.put(layer, definition);
    }

    public static void captureRawBakedModel(LayerDefinition definition, ModelPart root) {
        DEFINITIONS_BY_ROOT.put(root, definition);
    }

    public static void captureArmorModel(Model<?> model) {
        ArmorInfoBuilder builder = ARMOR_BUILDER.get();
        if (builder != null) {
            builder.model(model);
        }
    }

    public static <T> T withBuilderContext(RenderInfoBuilder builder, Function<RenderInfoBuilder, T> action) {
        RenderInfoBuilder previousBuilder = INFO_BUILDER.get();
        try {
            INFO_BUILDER.set(builder);
            return action.apply(builder);
        } finally {
            INFO_BUILDER.set(previousBuilder);
        }
    }

    public static void inBuilderContext(Consumer<RenderInfoBuilder> action) {
        RenderInfoBuilder builder = INFO_BUILDER.get();
        if (builder != null) {
            action.accept(builder);
        }
    }

    public static RenderRegistry generate(ServerLevel level) {
        RenderRegistry registry = new RenderRegistry();
        collectEntityInfo(registry, level);
        collectRegisteredModelLayers(registry);
        collectArmorInfo(registry);
        registry.modelLayers.putAll(BAKED_DEFINITIONS);
        collectBlockData(registry);
        registry.rebuildIndexes();
        LAYERS_BY_ROOT.clear();
        DEFINITIONS_BY_ROOT.clear();
        BAKED_DEFINITIONS.clear();
        return registry;
    }

    /**
     * Asks every Fabric armor renderer what it draws for the item it registered.
     *
     * <p>This is registration-driven: neither an item id nor a renderer class name has to resemble
     * its model layer, so the same path applies to custom armor from any mod.</p>
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void collectArmorInfo(RenderRegistry registry) {
        HumanoidModel<HumanoidRenderState> contextModel;
        try {
            contextModel = new HumanoidModel<>(Minecraft.getInstance().getEntityModels().bakeLayer(ModelLayers.PLAYER));
        } catch (Throwable e) {
            PolymerPatcherDumper.LOGGER.warn("Could not build the context model used to inspect custom armor", e);
            return;
        }

        int recorded = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            ArmorRenderer renderer = ArmorRendererRegistryImpl.get(item);
            if (renderer == null) {
                continue;
            }

            ItemStack stack = new ItemStack(item);
            Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
            if (equippable == null) {
                continue;
            }

            ArmorInfoBuilder builder = new ArmorInfoBuilder(item, contextModel.root());
            ARMOR_BUILDER.set(builder);
            try {
                renderer.render(new PoseStack(), DumpSubmitNodeCollector.INSTANCE, stack,
                    new HumanoidRenderState(), equippable.slot(), 0xF000F0, contextModel);
                registry.armorData.addAll(builder.entries);
                if (!builder.entries.isEmpty()) {
                    recorded++;
                }
            } catch (Throwable e) {
                PolymerPatcherDumper.LOGGER.warn("Failed to inspect custom armor renderer for {}",
                    BuiltInRegistries.ITEM.getKey(item), e);
            } finally {
                ARMOR_BUILDER.remove();
            }
        }

        PolymerPatcherDumper.LOGGER.info("Recorded custom armor models for {} item(s)", recorded);
    }

    private static void collectEntityInfo(RenderRegistry registry, ServerLevel level) {
        CameraRenderState cameraRenderState = new CameraRenderState();

        for (EntityType<?> entityType : BuiltInRegistries.ENTITY_TYPE) {
            Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entityType);
            try {
                Entity entity = entityType.create(level, EntitySpawnReason.COMMAND);
                if (entity == null) {
                    continue;
                }

                EntityRenderDispatcher entityRenderDispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
                if (entityRenderDispatcher.getRenderer(entity) == null) {
                    continue;
                }
                EntityRenderState renderState = entityRenderDispatcher.extractEntity(entity, 0.0F);
                EntityRenderer<?, ? super EntityRenderState> renderer = entityRenderDispatcher.getRenderer(renderState);

                // Judged by who draws it rather than by what it is called. Skipping everything named
                // "minecraft" was the same mistake made everywhere else: a mod may register its content
                // under the game's own name - a backport does, because it is adding what the next
                // version will ship - and FallDrop Backport's cushion was passed over here, so no model
                // was ever recorded for it and every cushion on the server was invisible. A renderer the
                // game itself wrote is the thing worth skipping, and that is what is asked now
                if (id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)
                    && renderer.getClass().getName().startsWith("net.minecraft.")) {
                    continue;
                }
                withBuilderContext(BUILDERS_BY_TYPE.get(entityType), (builder) -> {
                        renderer.submit(renderState, new PoseStack(), DumpSubmitNodeCollector.INSTANCE, cameraRenderState);

                        registry.modelLayers.putAll(builder.modelLayers());
                        registry.entityData.add(builder.build());
                        return null;
                    }
                );
            } catch (Throwable e) {
                PolymerPatcherDumper.LOGGER.warn("Failed to render entity: {}", entityType, e);
            }
        }
    }

    /**
     * Adds every model layer a mod has registered, whether or not anything was seen wearing it.
     * <p>
     * Walking the entities only finds the layers baked while one of them was being drawn, which misses
     * everything nothing happens to be wearing at the time - armour above all. That left the server
     * with the layers for mobs and none of the layers for the armour those mobs drop, so a piece with
     * a model of its own had nothing to be built from and simply went undrawn. The one exception was
     * the sombrero, which is on a mob by default and so got baked by accident.
     * <p>
     * Read reflectively: this is Fabric's own internal map rather than its public API, and a version
     * that moves it should cost the armour layers, not the whole dump.
     */
    @SuppressWarnings("unchecked")
    private static void collectRegisteredModelLayers(RenderRegistry registry) {
        Map<ModelLayerLocation, ?> providers;
        try {
            providers = registeredLayerProviders();
        } catch (Throwable e) {
            PolymerPatcherDumper.LOGGER.warn("Could not read the registered model layers; armour with a model of its own will not be dumped", e);
            return;
        }

        int added = 0;
        for (Map.Entry<ModelLayerLocation, ?> entry : providers.entrySet()) {
            if (registry.modelLayers.containsKey(entry.getKey())) {
                continue;
            }
            try {
                // The provider builds the layer definition on demand, the same call the game makes
                LayerDefinition definition = (LayerDefinition) entry.getValue().getClass()
                    .getMethod("createModelData").invoke(entry.getValue());
                if (definition != null) {
                    registry.modelLayers.put(entry.getKey(), definition);
                    added++;
                }
            } catch (Throwable e) {
                // One layer that will not build costs itself and nothing else
                PolymerPatcherDumper.LOGGER.warn("Failed to build model layer {}", entry.getKey(), e);
            }
        }

        PolymerPatcherDumper.LOGGER.info("Added {} model layer(s) that nothing was seen wearing", added);
    }

    @SuppressWarnings("unchecked")
    private static Map<ModelLayerLocation, ?> registeredLayerProviders() throws ReflectiveOperationException {
        String[] impls = {
            "net.fabricmc.fabric.impl.client.rendering.ModelLayerImpl",
            "net.fabricmc.fabric.impl.client.rendering.EntityModelLayerImpl"
        };

        ReflectiveOperationException failure = null;
        for (String impl : impls) {
            try {
                return (Map<ModelLayerLocation, ?>) Class.forName(impl).getField("PROVIDERS").get(null);
            } catch (ReflectiveOperationException e) {
                failure = e;
            }
        }
        throw failure;
    }

    private static void collectBlockData(RenderRegistry registry) {
        PolymerPatcherDumper.LOGGER.info("Collecting block data...");

        for (Block block : BuiltInRegistries.BLOCK) {
            boolean transparent = isTransparent(block.defaultBlockState());

            // Only run for its side effect: colouring a block in-world is what reaches BiomeColors,
            // and the mixin there notes which resolver was asked for. 26.2 split one getColor call
            // into a list of tint sources, so each of them is asked in turn
            PolymerPatcherDumper.COLOR_RESOLVER.remove();
            for (BlockTintSource tintSource : Minecraft.getInstance().getBlockColors().getTintSources(block.defaultBlockState())) {
                tintSource.colorInWorld(block.defaultBlockState(), BlockAndTintGetter.EMPTY, BlockPos.ZERO);
            }

            BlockInfo blockInfo = new BlockInfo(block, PolymerPatcherDumper.COLOR_RESOLVER.get(), transparent);
            registry.blockData.add(blockInfo);
        }
    }

    /**
     * Whether a block is drawn in anything but the solid pass.
     * <p>
     * There used to be one place to ask - {@code ItemBlockRenderTypes} named a chunk layer for any
     * block state. 26.2 pushed that decision down into the geometry: the layer now belongs to the
     * material each baked quad carries, so a block is asked for its model and the quads are read. A
     * block whose model says nothing counts as solid, which is what the old lookup returned for
     * anything it did not recognise.
     */
    private static boolean isTransparent(BlockState blockState) {
        BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(blockState);

        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(42), parts);

        for (BlockStateModelPart part : parts) {
            for (Direction direction : DIRECTIONS) {
                for (BakedQuad quad : part.getQuads(direction)) {
                    if (quad.materialInfo().layer() != ChunkSectionLayer.SOLID) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static Set<Identifier> expandEntityTextures(Set<Identifier> textures) {
        Set<Identifier> expandedTextures = new LinkedHashSet<>();
        for (Identifier texture : new HashSet<>(textures)) {
            expandedTextures.add(toModelTextureIdentifier(texture));
            if (shouldExpandSiblingTextures(texture)) {
                addSiblingTextures(texture, expandedTextures);
            }
        }
        return expandedTextures;
    }

    private static boolean shouldExpandSiblingTextures(Identifier defaultTexture) {
        Path path = Path.of(defaultTexture.getPath());
        Path parent = path.getParent();
        if (parent == null) {
            return false;
        }

        return !parent.equals(Path.of("textures/entity"));
    }

    private static void addSiblingTextures(Identifier defaultTexture, Set<Identifier> textures) {
        Path parent = Path.of(defaultTexture.getPath()).getParent();
        if (parent == null) {
            return;
        }

        Set<Pair<Identifier, IoSupplier<InputStream>>> files = PolymerPatcherDumper.GLOBAL_ASSETS.locateFiles(parent.toString());
        for (Pair<Identifier, IoSupplier<InputStream>> file : files) {
            Identifier texture = file.getFirst();
            if (!texture.getNamespace().equals(defaultTexture.getNamespace())) {
                continue;
            }

            Identifier modelTexture = toModelTextureIdentifier(texture);

            PolymerPatcherDumper.LOGGER.debug("{} sibling of {}", modelTexture.getPath(), texture);
            textures.add(modelTexture);
        }
    }

    private static Identifier toModelTextureIdentifier(Identifier texture) {
        String path = texture.getPath()
            .replace("textures/", "")
            .replace(".png", "");

        return Identifier.fromNamespaceAndPath(texture.getNamespace(), path);
    }

}
