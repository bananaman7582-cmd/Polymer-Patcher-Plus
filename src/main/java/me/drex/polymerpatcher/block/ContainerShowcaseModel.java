package me.drex.polymerpatcher.block;

import eu.pb4.factorytools.api.virtualentity.BlockModel;
import eu.pb4.factorytools.api.virtualentity.ItemDisplayElementUtil;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Shows what a block is holding, for a block whose contents only a modded client can see.
 * <p>
 * A block that displays what is inside it does so from a block entity renderer, and a dedicated server
 * runs none - so to anyone without the mod an Alex's Mobs capsid is an opaque shell whether or not
 * something is in it. There is no way to tell from the outside whether the thing you put in is still
 * there, which for a block whose whole purpose is transforming what it holds is most of the block.
 * <p>
 * The item is drawn by a display of our own instead, placed where the mod's own renderer places it:
 * at the middle of the block and slightly below it, held the way a dropped item is held, and turned
 * once to face the way the block faces. It does not spin. An earlier version of this turned it slowly
 * on the reasoning that a still item reads as a dropped one, which was an invention - the capsid holds
 * its item still, and a spin is both wrong and a packet several times a second per block.
 */
public class ContainerShowcaseModel extends BlockModel {

    private final ItemDisplayElement contents;
    private ItemStack shown = ItemStack.EMPTY;
    private boolean placed;

    private final boolean flat;

    public ContainerShowcaseModel(float viewRange, float height, float scale, boolean flat) {
        this.flat = flat;
        this.contents = ItemDisplayElementUtil.createSimple();
        this.contents.setViewRange(viewRange);
        this.contents.setItemDisplayContext(ItemDisplayContext.GROUND);
        this.contents.setTeleportDuration(0);
        this.contents.setTranslation(new Vector3f(0.0F, height, 0.0F));
        this.contents.setScale(new Vector3f(scale, scale, scale));
        this.contents.setInvisible(true);
        addElement(this.contents);
    }

    @Override
    protected void onTick() {
        super.onTick();

        // Set once, when the block is first known. Facing cannot change without the block being broken
        // and put back, which builds this again from nothing
        if (!this.placed) {
            this.placed = true;
            // Laid flat where the block holds it face-up, as an altar does, and stood up otherwise
            Quaternionf turned = new Quaternionf().rotateY(facingAngle());
            if (this.flat) {
                turned.rotateX((float) Math.toRadians(90));
            }
            this.contents.setLeftRotation(turned);
        }

        ItemStack holding = whatIsInside();
        // Compared rather than set every tick: setting an item sends it, and a capsid sitting
        // untouched should cost nothing at all
        if (!ItemStack.matches(holding, this.shown)) {
            this.shown = holding.copy();
            this.contents.setItem(this.shown);
            // An empty holder is hidden rather than drawn as nothing, so it is not a stray entity
            this.contents.setInvisible(this.shown.isEmpty());
        }
    }

    /** Which way the block faces, in radians, or straight ahead for a block that has no facing. */
    private float facingAngle() {
        var state = blockState();
        Direction facing = null;
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        } else if (state.hasProperty(BlockStateProperties.FACING)) {
            facing = state.getValue(BlockStateProperties.FACING);
        }
        return facing == null ? 0.0F : (float) Math.toRadians(-facing.toYRot());
    }

    private ItemStack whatIsInside() {
        if (getAttachment() == null) {
            return ItemStack.EMPTY;
        }
        BlockEntity blockEntity = getAttachment().getWorld().getBlockEntity(blockPos());
        if (!(blockEntity instanceof Container container)) {
            return ItemStack.EMPTY;
        }

        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty()) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }
}
