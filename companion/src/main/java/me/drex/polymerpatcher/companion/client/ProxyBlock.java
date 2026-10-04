package me.drex.polymerpatcher.companion.client;

import com.mojang.serialization.MapCodec;
import eu.pb4.polymer.core.api.utils.PolymerClientDecoded;
import me.drex.polymerpatcher.companion.shared.CompanionManifest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * This client's copy of one of the server's modded blocks.
 * <p>
 * It has the real block's name and properties, so Polymer swaps it in wherever the server has that
 * block, and it answers everything the client asks of a block - shape, light, sound, hardness - with what
 * the server said the real one answers. Its model is the mod's own, out of the server's resource pack.
 * It has no behaviour: anything the block does, the server does and tells the client about.
 */
public class ProxyBlock extends Block implements PolymerClientDecoded {

    /** The block being built right now; the state definition is made inside the constructor of Block. */
    static final ThreadLocal<ProxyStates> BUILDING = new ThreadLocal<>();

    private final ProxyStates states;

    private ProxyBlock(Properties properties, ProxyStates states) {
        super(properties);
        this.states = states;
        states.bind(this);
    }

    static ProxyBlock create(Identifier id, ProxyStates states) {
        BUILDING.set(states);
        try {
            return new ProxyBlock(propertiesOf(id, states), states);
        } finally {
            BUILDING.remove();
        }
    }

    /** The settings the game reads off a block's properties rather than asking the block. */
    static BlockBehaviour.Properties propertiesOf(Identifier id, ProxyStates states) {
        CompanionManifest.BlockEntry entry = states.entry;
        BlockBehaviour.Properties properties = BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, id))
            .strength(entry.destroyTime(), entry.explosionResistance())
            .friction(entry.friction())
            .speedFactor(entry.speedFactor())
            .jumpFactor(entry.jumpFactor())
            .noLootTable()
            .lightLevel(state -> states.of(state).entry().light())
            .isRedstoneConductor((state, level, pos) -> states.of(state).has(CompanionManifest.STATE_REDSTONE_CONDUCTOR))
            .isSuffocating((state, level, pos) -> states.of(state).has(CompanionManifest.STATE_SUFFOCATING))
            .isViewBlocking((state, level, pos) -> states.of(state).has(CompanionManifest.STATE_VIEW_BLOCKING))
            .emissiveRendering(state -> states.of(state).has(CompanionManifest.STATE_EMISSIVE));
        if (!entry.states().isEmpty()) {
            properties.sound(states.tables.sound(entry.states().getFirst().sound()));
            if ((entry.states().getFirst().flags() & CompanionManifest.STATE_SOLID) != 0) {
                properties.forceSolidOn();
            } else {
                properties.forceSolidOff();
            }
        }
        int flags = entry.flags();
        if ((flags & CompanionManifest.BLOCK_CAN_OCCLUDE) == 0) properties.noOcclusion();
        if ((flags & CompanionManifest.BLOCK_REQUIRES_TOOL) != 0) properties.requiresCorrectToolForDrops();
        if ((flags & CompanionManifest.BLOCK_DYNAMIC_SHAPE) != 0) properties.dynamicShape();
        if ((flags & CompanionManifest.BLOCK_REPLACEABLE) != 0) properties.replaceable();
        if ((flags & CompanionManifest.BLOCK_NO_TERRAIN_PARTICLES) != 0) properties.noTerrainParticles();
        if ((flags & CompanionManifest.BLOCK_OFFSET_XYZ) != 0) {
            properties.offsetType(BlockBehaviour.OffsetType.XYZ);
        } else if ((flags & CompanionManifest.BLOCK_OFFSET_XZ) != 0) {
            properties.offsetType(BlockBehaviour.OffsetType.XZ);
        }
        return properties;
    }

    /** The states while the game is still building this block, when the field is not yet set. */
    private ProxyStates states() {
        ProxyStates set = states;
        return set != null ? set : BUILDING.get();
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        ProxyStates building = BUILDING.get();
        if (building != null) {
            for (Property<?> property : building.properties) {
                builder.add(property);
            }
        }
    }

    @Override
    public boolean shouldDecodePolymer() {
        return CompanionState.decoding();
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return MapCodec.unit(this);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return states().of(state).outline();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return states().of(state).collision();
    }

    @Override
    protected VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return states().of(state).visual();
    }

    @Override
    protected VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return states().of(state).interaction();
    }

    @Override
    protected VoxelShape getOcclusionShape(BlockState state) {
        return states().of(state).occlusion();
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return states().of(state).has(CompanionManifest.STATE_DRAW_MODEL) ? RenderShape.MODEL : RenderShape.INVISIBLE;
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state) {
        return states().of(state).has(CompanionManifest.STATE_SKYLIGHT);
    }

    @Override
    protected int getLightDampening(BlockState state) {
        return states().of(state).entry().lightDampening();
    }

    @Override
    protected boolean useShapeForLightOcclusion(BlockState state) {
        return states().of(state).has(CompanionManifest.STATE_SHAPE_LIGHT_OCCLUSION);
    }

    @Override
    protected SoundType getSoundType(BlockState state) {
        return states().of(state).sound();
    }

    @Override
    protected boolean skipRendering(BlockState state, BlockState neighbour, Direction direction) {
        return (states().entry.flags() & CompanionManifest.BLOCK_SKIPS_OWN_FACES) != 0 && neighbour.is(this)
            || super.skipRendering(state, neighbour, direction);
    }

    @Override
    protected float getMaxHorizontalOffset() {
        return states().entry.maxHorizontalOffset();
    }

    @Override
    protected float getMaxVerticalOffset() {
        return states().entry.maxVerticalOffset();
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) {
            return Fluids.WATER.getSource(false);
        }
        return super.getFluidState(state);
    }

    /** How this block's biome tint was described, for the colours registered once the client is up. */
    int tint() {
        return states().entry.tint();
    }
}
