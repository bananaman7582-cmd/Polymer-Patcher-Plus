package me.drex.polymerpatcher.compat.alexscaves;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.joml.Quaternionf;

/** Exact static transforms used by Alex's Caves block-entity renderers. */
public final class AlexsCavesBlockTransforms {

    private AlexsCavesBlockTransforms() {
    }

    public static boolean apply(PoseStack pose, BlockState state) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null || !id.getNamespace().equals("alexscaves")) {
            return false;
        }

        if (id.getPath().equals("conversion_crucible")) {
            // The generated item geometry is already centred on the display's origin. Alex's renderer
            // starts with block-space geometry, hence its extra +.5 on every axis; carrying that centre
            // over a second time put the captured model half a block sideways and a full block in the sky.
            pose.translate(0, 1, 0);
            pose.mulPose(new Quaternionf().rotateX((float) -Math.PI));
            return true;
        }

        if (id.getPath().equals("beholder")) {
            // BeholderBlockRenderer starts at (0.5, 1.5, 0.5) before turning the Citadel model
            // upside-down. BlockModel's attachment already supplies the horizontal half-block centre,
            // but the generic Citadel fallback only raises the model by 0.5 and leaves half of the eye
            // below the floor. The captured item model already contributes the other half block, so
            // retaining the renderer's full 1.5-block origin put the corrected eye half a block aloft.
            pose.translate(0, 1.0F, 0);
            pose.mulPose(new Quaternionf().rotateX((float) -Math.PI));
            return true;
        }

        if (id.getPath().equals("copper_valve")) {
            Direction facing = state.getValueOrElse(BlockStateProperties.FACING, Direction.UP);
            switch (facing) {
                case UP -> pose.translate(0, 1, 0);
                case DOWN -> pose.translate(0, -1, 0);
                case NORTH -> pose.translate(0, 0, -1);
                case EAST -> pose.translate(1, 0, 0);
                case SOUTH -> pose.translate(0, 0, 1);
                case WEST -> pose.translate(-1, 0, 0);
            }
            pose.mulPose(facing.getOpposite().getRotation());
            return true;
        }

        if (id.getPath().equals("nuclear_furnace")) {
            // NuclearFurnaceBlockRenderer draws the complete eight-block structure from the one
            // corner which owns the block entity. Its model is already rooted one block left/forward;
            // reproduce the renderer's transform exactly instead of the single-block fallback.
            // A block-entity renderer starts at the owning block's corner. The virtual display is
            // rooted at its centre, so the corner is half a block back on every axis - down as well as
            // west and north. Moving only X and Z, and then shaving a sixteenth off the renderer's own
            // 1.5 to cancel a hover that was really the missing half block, left the whole structure
            // seven sixteenths of a block in the air.
            //
            // So this is the renderer, step for step, from where it starts: NuclearFurnaceBlockRenderer
            // turns the pose 180 degrees about Z and moves it -1.5 along Y, and nothing else. Read out of
            // its bytecode (Alex's Caves 1.1.1) rather than tuned by eye.
            pose.translate(-0.5F, -0.5F, -0.5F);
            pose.mulPose(new Quaternionf().rotateZ((float) Math.PI));
            pose.translate(0, -1.5F, 0);
            return true;
        }
        return false;
    }

    /** Applies state read by a renderer-owned model before its resolved parts are visited. */
    public static boolean applyModel(Object model, BlockState state) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null || !id.equals(Identifier.fromNamespaceAndPath("alexscaves", "nuclear_furnace"))
            || !model.getClass().getName().equals(
                "com.github.alexmodguy.alexscaves.client.model.NuclearFurnaceModel")) {
            return false;
        }

        Direction facing = state.getValueOrElse(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        try {
            java.lang.reflect.Method setup = model.getClass().getMethod("setupAnim",
                net.minecraft.world.entity.Entity.class,
                float.class, float.class, float.class, float.class, float.class);
            // Exact argument order used by NuclearFurnaceBlockRenderer. Criticality/age/waste are
            // intentionally at their stable off-state values for this static presentation.
            setup.invoke(model, null, facing.toYRot() - 180.0F, 0.0F, 0.0F, 0.0F, 0.0F);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
