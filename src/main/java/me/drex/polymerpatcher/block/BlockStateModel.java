package me.drex.polymerpatcher.block;

import eu.pb4.factorytools.api.block.model.generic.BlockStateModelManager;
import eu.pb4.factorytools.api.virtualentity.BlockModel;
import eu.pb4.factorytools.api.virtualentity.ItemDisplayElementUtil;
import eu.pb4.polymer.virtualentity.api.attachment.BlockAwareAttachment;
import eu.pb4.polymer.virtualentity.api.attachment.BlockBoundAttachment;
import eu.pb4.polymer.virtualentity.api.attachment.HolderAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.config.BlockConfig;
import me.drex.polymerpatcher.config.ConfigManager;
import me.drex.polymerpatcher.dump.data.RenderRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.MapItemColor;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BlockStateModel extends BlockModel {
    private final List<ItemDisplayElement> modelElements = new ArrayList<>();
    private final float viewRange;
    private final RandomSource ambientRandom = RandomSource.create();

    /**
     * The box the client tests against the camera before drawing a display, in blocks.
     * <p>
     * A display entity with no size is never culled: the game draws it every frame whether it is in front of
     * the camera, behind it or inside a wall. Every modded block drawn by a display was exactly that, so a
     * cave full of vines and ferns was drawn in full in every direction at once. Two blocks each way leaves
     * room for models that overhang their block.
     */
    private static final float CULL_WIDTH = 2.0F;
    private static final float CULL_HEIGHT = 2.0F;

    /**
     * The culling box grows upward from the display's position, and a block display sits at the centre of
     * its block - so without this the bottom half of every block lay outside its own box and could vanish at
     * the edge of the screen. The display is moved down to the bottom of the block and its model moved back
     * up by the same amount, which draws it exactly where it was.
     */
    private static final Vec3 TO_BLOCK_BOTTOM = new Vec3(0, -0.5, 0);
    private static final Vector3f BACK_TO_CENTRE = new Vector3f(0, 0.5F, 0);

    /** Whether a block state's shape is under half a block, worked out once per state. */
    private static final Map<BlockState, Boolean> SMALL = new ConcurrentHashMap<>();

    /**
     * Whether this holder draws the block, or only does the things that go on around it.
     * <p>
     * A holder is needed for two quite different reasons and they were being served by the same thing.
     * One is that the block has no vanilla carrier and a display is the only way it can be seen at all.
     * The other is that the block gives off something - a flame, a wisp, a drip - which is a matter of
     * particles and nothing to do with how the block is drawn.
     * <p>
     * A block can want the second while being perfectly well drawn already, and then drawing it again
     * puts two copies of the same model in the same place. Two identical surfaces at identical depth is
     * exactly the condition that makes a texture flicker and tear as you move, which is what was
     * happening to every bioluminescent torch: it had a carrier, it was already right, and it was then
     * drawn a second time on top of itself.
     */
    private final boolean drawsBlock;

    public BlockStateModel(float viewRange) {
        this(viewRange, true);
    }

    private BlockStateModel(float viewRange, boolean drawsBlock) {
        this.viewRange = viewRange;
        this.drawsBlock = drawsBlock;
    }

    /**
     * A display for this block, drawn out to the distance the config gives blocks of its size.
     */
    public static BlockStateModel forBlock(BlockState state) {
        BlockConfig blocks = ConfigManager.config().blocks;
        return new BlockStateModel(isSmall(state) ? blocks.smallDisplayViewRange : blocks.displayViewRange);
    }

    /**
     * A display for a block that is one only because the carriers ran out: drawn properly, but not far.
     * See {@code BlockConfig.fallbackDisplayViewRange}.
     */
    public static BlockStateModel fallback(BlockState state) {
        BlockConfig blocks = ConfigManager.config().blocks;
        float usual = isSmall(state) ? blocks.smallDisplayViewRange : blocks.displayViewRange;
        return new BlockStateModel(Math.min(usual, blocks.fallbackDisplayViewRange));
    }

    public static BlockStateModel midRange() {
        return new BlockStateModel(ConfigManager.config().blocks.displayViewRange);
    }

    /**
     * A holder for a block that is already drawn properly and only needs its effects run.
     */
    public static BlockStateModel effectsOnly() {
        return new BlockStateModel(ConfigManager.config().blocks.displayViewRange, false);
    }

    /**
     * Whether a block is a small decoration - a plant, a vine, an egg - by the volume of its shape.
     * <p>
     * These are the most numerous displays by far and the hardest to make out at a distance, so they are
     * given the shorter view range. A block whose shape cannot be read without a world is treated as full size.
     */
    static boolean isSmall(BlockState state) {
        return SMALL.computeIfAbsent(state, s -> {
            try {
                VoxelShape shape = s.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                if (shape.isEmpty()) {
                    return true;
                }
                double volume = 0;
                for (AABB box : shape.toAabbs()) {
                    volume += box.getXsize() * box.getYsize() * box.getZsize();
                }
                return volume < 0.5;
            } catch (Throwable e) {
                return false;
            }
        });
    }

    public void notifyUpdate(HolderAttachment.UpdateType updateType) {
        super.notifyUpdate(updateType);
        if (updateType == BlockAwareAttachment.BLOCK_STATE_UPDATE) {
            this.applyModel(BlockStateModelManager.get(this.blockState()), this.blockPos());
        }
    }

    @Override
    protected void onAttachmentSet(HolderAttachment attachment, @Nullable HolderAttachment oldAttachment) {
        super.onAttachmentSet(attachment, oldAttachment);
        this.applyModel(BlockStateModelManager.get(this.blockState()), this.blockPos());
    }

    @Override
    protected void onTick() {
        AmbientBlockEffects.tick(this, this.blockState(), this.blockPos(), this.ambientRandom);
        super.onTick();
    }

    private void applyModel(List<BlockStateModelManager.ModelGetter> models, BlockPos pos) {
        // Nothing to draw: the client is already seeing a real block wearing the right model, and a
        // second copy of it in the same place is not more visible, it is a flickering mess
        if (!this.drawsBlock) {
            while (!this.modelElements.isEmpty()) {
                this.removeElement(this.modelElements.removeLast());
            }
            return;
        }

        RandomSource random = RandomSource.create(this.blockState().getSeed(pos));
        int i = 0;

        while (models.size() < this.modelElements.size()) {
            this.removeElement(this.modelElements.removeLast());
        }

        for (; i < models.size(); ++i) {
            boolean newModel = false;
            ItemDisplayElement element;
            if (this.modelElements.size() <= i) {
                element = ItemDisplayElementUtil.createSimple();
                element.setViewRange(this.viewRange);
                element.setDisplaySize(CULL_WIDTH, CULL_HEIGHT);
                element.setOffset(TO_BLOCK_BOTTOM);
                element.setTranslation(new Vector3f(BACK_TO_CENTRE));
                element.setTeleportDuration(0);
                element.setItemDisplayContext(ItemDisplayContext.NONE);
                element.setYaw(180.0F);
                newModel = true;
                this.modelElements.add(element);
            } else {
                element = this.modelElements.get(i);
            }

            BlockStateModelManager.ModelData model = models.get(i).getModel(random);
            element.setItem(model.stack());
            element.setLeftRotation(model.quaternionfc());
            this.setupElement(element, i, pos);
            if (newModel) {
                this.addElement(element);
            } else {
                element.tick();
            }
        }
    }

    protected void setupElement(ItemDisplayElement element, int i, BlockPos pos) {
        if (!(getAttachment() instanceof BlockBoundAttachment blockBoundAttachment)) {
            return;
        }
        RenderRegistry.BlockInfo blockInfo = PolymerPatcher.getRenderRegistry().blockInfoByBlock.get(blockBoundAttachment.getBlockState().getBlock());
        if (blockInfo != null && blockInfo.biomeColor() != null) {
            int color = getAverageColor(blockBoundAttachment.getChunk(), pos, blockInfo.biomeColor());
            if (color != -1) {
                ItemStack item = element.getItem().copy();
                item.set(DataComponents.MAP_COLOR, new MapItemColor(color));
                element.setItem(item);
            }
        }
    }

    private static int getAverageColor(LevelChunk chunk, BlockPos blockPos, ColorResolver colorResolver) {
        Holder<Biome> biome = chunk.getNoiseBiome(QuartPos.fromBlock(blockPos.getX()), QuartPos.fromBlock(blockPos.getY()), QuartPos.fromBlock(blockPos.getZ()));
        return colorResolver.getColor(biome.value(), blockPos.getX(), blockPos.getZ());
    }
}
