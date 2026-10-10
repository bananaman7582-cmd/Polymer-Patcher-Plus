package me.drex.polymerpatcher.block;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Small extension points for block visuals which cannot be inferred from a blockstate model.
 *
 * <p>The ordinary path remains entirely data driven. A compatibility adapter only comes here for a
 * semantic fact which exists in client code alone: that an invisible portal really is an end-portal
 * surface, or that a block entity draws a live line of text above an otherwise ordinary model. Keeping
 * the hooks generic prevents those facts from becoming mod-name branches in the block allocator.</p>
 */
public final class BlockPresentationRules {
    @FunctionalInterface
    public interface CarrierRule {
        @Nullable BlockState apply(BlockState state);
    }

    @FunctionalInterface
    public interface HolderRule {
        @Nullable ElementHolder create(ServerLevel level, BlockPos pos, BlockState state);
    }

    @FunctionalInterface
    public interface TickRule {
        boolean applies(BlockState state);
    }

    private static final List<CarrierRule> CARRIER_RULES = new CopyOnWriteArrayList<>();
    private static final List<HolderRule> HOLDER_RULES = new CopyOnWriteArrayList<>();
    private static final List<TickRule> TICK_RULES = new CopyOnWriteArrayList<>();
    private static final List<TickRule> MODELLED_RULES = new CopyOnWriteArrayList<>();

    private BlockPresentationRules() {
    }

    public static void registerCarrier(CarrierRule rule) {
        CARRIER_RULES.add(rule);
    }

    public static void registerHolder(HolderRule rule) {
        HOLDER_RULES.add(rule);
    }

    public static void registerTicking(TickRule rule) {
        TICK_RULES.add(rule);
    }

    /**
     * Blocks which say they are invisible because their renderer draws them, but which the pack has been
     * given a real model for. Without this they are sent as air, whatever model the pack holds.
     */
    public static void registerModelled(TickRule rule) {
        MODELLED_RULES.add(rule);
    }

    public static boolean modelled(BlockState state) {
        for (TickRule rule : MODELLED_RULES) {
            if (rule.applies(state)) {
                return true;
            }
        }
        return false;
    }

    public static @Nullable BlockState carrier(BlockState state) {
        for (CarrierRule rule : CARRIER_RULES) {
            BlockState result = rule.apply(state);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    public static @Nullable ElementHolder holder(ServerLevel level, BlockPos pos, BlockState state) {
        for (HolderRule rule : HOLDER_RULES) {
            ElementHolder result = rule.create(level, pos, state);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    public static boolean ticks(BlockState state) {
        for (TickRule rule : TICK_RULES) {
            if (rule.applies(state)) {
                return true;
            }
        }
        return false;
    }
}
