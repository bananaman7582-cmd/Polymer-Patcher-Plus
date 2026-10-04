package me.drex.polymerpatcher.companion.client;

import eu.pb4.polymer.core.api.utils.PolymerClientDecoded;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.PushReaction;

/** The block a {@link ProxyFluid} sits in, under the real fluid block's name. */
public class ProxyLiquidBlock extends LiquidBlock implements PolymerClientDecoded {

    private final ProxyStates states;
    private static final ThreadLocal<ProxyStates> BUILDING = new ThreadLocal<>();

    static ProxyLiquidBlock create(Identifier id, FlowingFluid fluid, ProxyStates states) {
        BUILDING.set(states);
        try {
            return new ProxyLiquidBlock(id, fluid, states);
        } finally {
            BUILDING.remove();
        }
    }

    private ProxyLiquidBlock(Identifier id, FlowingFluid fluid, ProxyStates states) {
        super(fluid, BlockBehaviour.Properties.of()
            .setId(ResourceKey.create(Registries.BLOCK, id))
            .replaceable()
            .noCollision()
            .strength(states.entry.explosionResistance())
            .pushReaction(PushReaction.DESTROY)
            .noLootTable()
            .liquid()
            .sound(SoundType.EMPTY)
            .lightLevel(state -> states.of(state).entry().light()));
        this.states = states;
        states.bind(this);
    }

    @Override
    public boolean shouldDecodePolymer() {
        return CompanionState.decoding();
    }

    @Override
    protected int getLightDampening(BlockState state) {
        ProxyStates set = states != null ? states : BUILDING.get();
        return set == null ? super.getLightDampening(state) : set.of(state).entry().lightDampening();
    }
}
