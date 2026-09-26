package me.drex.polymerpatcher.util;

import eu.pb4.sgui.api.gui.SimpleGui;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

/**
 * A vanilla screen whose live slots still belong to a mod's real menu.
 *
 * <p>This is the reusable part of the Nuclear Furnace bridge: moving a {@link net.minecraft.world.inventory.Slot}
 * changes only where the vanilla client clicks it. Its container, placement rules, extraction callbacks and
 * recipe ownership remain the mod's. Keeping the original menu alive also preserves its distance/block validity
 * check and its close callback.</p>
 */
public class RedirectedMenuGui extends SimpleGui {
    protected final AbstractContainerMenu wrapped;
    private boolean removed;

    public RedirectedMenuGui(
        MenuType<?> type,
        ServerPlayer player,
        AbstractContainerMenu wrapped,
        Component title
    ) {
        // SGui supplies the ordinary 36 player slots. Only the mod-owned slots are redirected below.
        super(type, player, false);
        this.wrapped = wrapped;
        setTitle(title);
    }

    protected final void redirect(int guiSlot, int wrappedSlot) {
        setSlot(guiSlot, wrapped.slots.get(wrappedSlot));
    }

    @Override
    public void onTick() {
        super.onTick();
        if (!wrapped.stillValid(player)) {
            close(false);
        }
    }

    @Override
    public void onRemoved() {
        super.onRemoved();
        if (!removed) {
            removed = true;
            wrapped.removed(player);
        }
    }
}
