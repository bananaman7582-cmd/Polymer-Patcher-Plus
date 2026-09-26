package me.drex.polymerpatcher.compat.alexscaves;

import me.drex.polymerpatcher.entity.render.RenderCaptureRules;
import me.drex.polymerpatcher.entity.armor.ArmorModels;
import me.drex.polymerpatcher.resources.ResourceHelper;
import me.drex.polymerpatcher.item.HeldItemPresentations;
import me.drex.polymerpatcher.item.HeldItemProbe;
import me.drex.polymerpatcher.block.ShowcaseBlocks;

/** Registers Alex's Caves facts with the mod-independent renderer capture engine. */
public final class AlexsCavesRenderRules {
    private static boolean initialized;

    private AlexsCavesRenderRules() {
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        RenderCaptureRules.registerItem(AlexsCavesDaggers::projectileStack);
        RenderCaptureRules.registerBlock(AlexsCavesEntityTransforms::adjustCapturedBlock);
        RenderCaptureRules.registerBoxFilter(AlexsCavesSubmarines::isInvisibleWaterMask);
        RenderCaptureRules.registerRideOffset(AlexsCavesSubmarines::rideOffset);
        RenderCaptureRules.registerTexture(AlexsCavesGhosts::textureFor);
        RenderCaptureRules.registerStaticBlock(AlexsCavesBlockTransforms::apply);
        RenderCaptureRules.registerStaticModel(AlexsCavesBlockTransforms::applyModel);
        RenderCaptureRules.registerExtraTexture((entityId, texture) ->
            "alexscaves:dinosaur_spirit".equals(entityId)
                ? AlexsCavesGhosts.registerTexture(texture) : null);
        for (int fadeStage = 0; fadeStage <= 8; fadeStage++) {
            final int stage = fadeStage;
            RenderCaptureRules.registerExtraTexture((entityId, texture) ->
                "alexscaves:dinosaur_spirit".equals(entityId)
                    ? AlexsCavesGhosts.registerTexture(texture, stage) : null);
        }
        RenderCaptureRules.registerOuterTexture(AlexsCavesGhosts::textureForEntity);
        RenderCaptureRules.registerExtraTexture(AlexsCavesSlimes::registerTexture);
        RenderCaptureRules.registerOuterTexture(AlexsCavesSlimes::textureFor);
        RenderCaptureRules.registerExtraModelTexture(AlexsCavesSlimes::extraModelTexture);
        RenderCaptureRules.registerAssets(AlexsCavesGhosts::generateTextures);
        RenderCaptureRules.registerAssets(AlexsCavesDaggers::generate);
        RenderCaptureRules.registerAssets(AlexsCavesSlimes::generateTextures);
        RenderCaptureRules.registerAssets(NuclearFurnaceUiAssets::generate);
        HeldItemProbe.registerRenderer("alexscaves",
            "com.github.alexmodguy.alexscaves.client.render.item.ACItemstackRenderer",
            "com.github.alexmodguy.alexscaves.client.render.compat.MultiBufferSource");
        ShowcaseBlocks.register(net.minecraft.resources.Identifier.fromNamespaceAndPath(
            "alexscaves", "abyssal_altar"), new ShowcaseBlocks.Showcase(0.52F, 0.5F, true));

        ArmorModels.registerFallback("Alex's Caves conventional", sink ->
            AlexsCavesArmorModels.discover((piece, shape) -> sink.add(
                piece.item(), piece.layer(), shape.root(),
                piece.texture(texture -> ResourceHelper.getAsset(
                    texture.getNamespace(), texture.getPath()) != null), shape.model())));

        HeldItemPresentations.register(new HeldItemPresentations.Provider() {
            @Override
            public String definition(eu.pb4.polymer.resourcepack.api.ResourcePackBuilder builder,
                                     String namespace, String itemPath,
                                     net.minecraft.resources.Identifier shape) {
                return AlexsCavesHeldItems.definition(builder, namespace, itemPath, shape);
            }

            @Override
            public Float geometryScale(String namespace, String itemPath) {
                return namespace.equals("alexscaves")
                    ? AlexsCavesHeldItems.geometryScale(namespace, itemPath) : null;
            }

            @Override
            public void addTextureVariants(eu.pb4.polymer.resourcepack.api.ResourcePackBuilder builder,
                                           String namespace, String itemPath, com.google.gson.JsonObject baseModel,
                                           net.minecraft.resources.Identifier modelId) {
                AlexsCavesHeldItems.addTextureVariants(builder, namespace, itemPath, baseModel, modelId);
            }

            @Override
            public void modifyItemStack(net.minecraft.world.item.ItemStack out,
                                        net.minecraft.world.item.ItemStack original) {
                AlexsCavesHeldItems.modifyItemStack(out, original);
            }
        });
    }
}
