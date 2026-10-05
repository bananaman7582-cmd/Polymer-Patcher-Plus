package me.drex.polymerpatcher.mixin.factorytools;

import eu.pb4.factorytools.api.util.VirtualDestroyStage;
import eu.pb4.factorytools.impl.ServerPlayNetExtF;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import me.drex.polymerpatcher.block.AutomaticFactoryBlock;
import me.drex.polymerpatcher.impl.VirtualDestroyStageExt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes FactoryTools' breaking texture follow the outline of an automatically patched block.
 *
 * <p>FactoryTools normally draws the destroy stage with one {@code cube_all} item display. That is
 * correct for a cube, but it puts a full-block crack texture around torches, stairs, fences, plants,
 * and every other non-cube model. The actual server block still has its original outline, so it can
 * be represented by one small destroy-stage cube per box in that outline.</p>
 */
@Mixin(value = VirtualDestroyStage.class, remap = false)
public abstract class VirtualDestroyStageMixin extends ElementHolder implements VirtualDestroyStageExt {
    @Unique
    /**
     * More boxes than this after merging and the outline is wrapped in one crack instead. A crack per box reads
     * as several blocks breaking at once, which is what a dozen tiny boxes looked like.
     */
    private static final int POLYMER_PATCHER$MAX_PARTS = 4;

    @Unique
    private static final float POLYMER_PATCHER$OVERLAY_SCALE = 1.02f;

    @Unique
    private static final float POLYMER_PATCHER$OVERLAY_EXPANSION = 0.02f;

    @Shadow
    @Final
    private ItemDisplayElement main;

    @Unique
    private List<ItemDisplayElement> polymer_patcher$extraParts;

    @Inject(method = "updateState", at = @At("TAIL"))
    private static void polymer_patcher$applyRealShape(ServerPlayer player, BlockPos pos, BlockState state, int stage,
                                                       CallbackInfoReturnable<Boolean> cir) {
        VirtualDestroyStage holder = ((ServerPlayNetExtF) player.connection).factorytools$getVirtualDestroyStage();
        ((VirtualDestroyStageExt) holder).polymer_patcher$setBlockShape(player, pos, state, stage);
    }

    @Override
    public void polymer_patcher$setBlockShape(ServerPlayer player, BlockPos pos, BlockState state, int stage) {
        var overlay = PolymerSyncedObject.getSyncedObject(BuiltInRegistries.BLOCK, state.getBlock());
        if (stage < 0 || !(overlay instanceof AutomaticFactoryBlock)) {
            polymer_patcher$restoreCube(stage);
            return;
        }

        VoxelShape shape;
        try {
            // The selection outline is the closest server-side description of what the player sees.
            // Visual shapes are primarily for occlusion and can be a full cube even for a thin plant,
            // which used to put a giant breaking box around Strangle Ferns.
            CollisionContext context = CollisionContext.of(player);
            shape = state.getShape(player.level(), pos, context);
            if (shape.isEmpty()) {
                shape = state.getVisualShape(player.level(), pos, context);
            }
        } catch (Throwable ignored) {
            shape = state.getCollisionShape(player.level(), pos);
        }

        if (shape.isEmpty()) {
            shape = state.getCollisionShape(player.level(), pos);
        }

        if (shape.isEmpty()) {
            // A block with no selectable shape has nothing sensible to wrap. Retaining FactoryTools'
            // original cube is safer than creating an invisible, permanently stuck mining marker.
            polymer_patcher$restoreCube(stage);
            return;
        }

        // Merged first. A mod is free to build its outline out of many small boxes - some full blocks are a
        // cube made of eight - and a crack drawn per box put a separate crack on every tile of one block,
        // which looked like several blocks breaking at once. A full block always gets the single crack
        // FactoryTools would draw.
        shape = shape.optimize();
        List<AABB> boxes = Block.isShapeFullBlock(shape) ? List.of(new AABB(0, 0, 0, 1, 1, 1)) : shape.toAabbs();
        if (boxes.size() > POLYMER_PATCHER$MAX_PARTS) {
            boxes = List.of(shape.bounds());
        }

        int visualStage = stage;
        if (stage == 0) {
            // The first server event is sometimes an unconditional zero emitted when mining starts;
            // the authoritative accumulated progress does not arrive until the following update.
            // On a quickly-mined block that leaves stage zero sitting there for most of the break,
            // then jumping several frames at once. The block's own per-tick destroy progress uses the
            // real tool, effects and hardness, and is exactly the first term ServerPlayerGameMode uses,
            // so it is a safe prediction for this one initial frame. Later stages remain server-owned.
            try {
                float firstTick = state.getDestroyProgress(player, player.level(), pos);
                if (Float.isFinite(firstTick) && firstTick > 0) {
                    // Never jump several frames ahead of the authoritative accumulator. That made a
                    // two-tick block appear half broken immediately, sit there, then finish.
                    visualStage = Math.min(1, (int) (firstTick * 10.0F));
                }
            } catch (Throwable ignored) {
                // A modded block can compute progress through arbitrary code. If it cannot do so here,
                // the ordinary stage zero is still valid and the next authoritative event replaces it.
            }
        }

        ItemStack destroyStage = VirtualDestroyStage.MODELS[Math.min(visualStage, VirtualDestroyStage.MODELS.length - 1)].get();
        polymer_patcher$configure(this.main, destroyStage, boxes.getFirst());

        List<ItemDisplayElement> extraParts = polymer_patcher$extraParts();
        while (extraParts.size() > boxes.size() - 1) {
            ItemDisplayElement removed = extraParts.removeLast();
            this.removeElement(removed);
        }

        for (int i = 1; i < boxes.size(); i++) {
            ItemDisplayElement element;
            if (i - 1 < extraParts.size()) {
                element = extraParts.get(i - 1);
                polymer_patcher$configure(element, destroyStage, boxes.get(i));
            } else {
                element = new ItemDisplayElement();
                element.setItemDisplayContext(ItemDisplayContext.NONE);
                element.setViewRange(this.main.getViewRange());
                polymer_patcher$configure(element, destroyStage, boxes.get(i));
                extraParts.add(element);
                this.addElement(element);
            }
        }

        // setState() has already propagated the new crack stage. This second tick propagates the
        // shape transforms; both updates are emitted in the same server tick.
        this.tick();
    }

    @Unique
    private void polymer_patcher$restoreCube(int stage) {
        this.main.setItem(stage < 0
            ? ItemStack.EMPTY
            : VirtualDestroyStage.MODELS[Math.min(stage, VirtualDestroyStage.MODELS.length - 1)].get());
        this.main.setScale(new Vector3f(POLYMER_PATCHER$OVERLAY_SCALE));
        this.main.setTranslation(new Vector3f());

        if (this.polymer_patcher$extraParts != null) {
            for (ItemDisplayElement element : List.copyOf(this.polymer_patcher$extraParts)) {
                this.removeElement(element);
            }
            this.polymer_patcher$extraParts.clear();
        }

        this.tick();
    }

    @Unique
    private List<ItemDisplayElement> polymer_patcher$extraParts() {
        if (this.polymer_patcher$extraParts == null) {
            this.polymer_patcher$extraParts = new ArrayList<>();
        }
        return this.polymer_patcher$extraParts;
    }

    @Unique
    private static void polymer_patcher$configure(ItemDisplayElement element, ItemStack destroyStage, AABB box) {
        float sizeX = (float) box.getXsize();
        float sizeY = (float) box.getYsize();
        float sizeZ = (float) box.getZsize();
        element.setItem(destroyStage);
        element.setScale(new Vector3f(
            sizeX + POLYMER_PATCHER$OVERLAY_EXPANSION,
            sizeY + POLYMER_PATCHER$OVERLAY_EXPANSION,
            sizeZ + POLYMER_PATCHER$OVERLAY_EXPANSION
        ));
        element.setTranslation(new Vector3f(
            (float) ((box.minX + box.maxX) * 0.5 - 0.5),
            (float) ((box.minY + box.maxY) * 0.5 - 0.5),
            (float) ((box.minZ + box.maxZ) * 0.5 - 0.5)
        ));
    }
}
