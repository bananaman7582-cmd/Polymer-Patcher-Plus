package me.drex.polymerpatcher.compat.illagerinvasion;

import me.drex.polymerpatcher.mixin.sync.AbstractContainerMenuAccessor;
import me.drex.polymerpatcher.util.RedirectedMenuGui;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.MenuType;

import java.util.List;

/** A vanilla 9x3 view backed by the Imbuing Table's four real slots. */
final class ImbuingUi extends RedirectedMenuGui {
    static final int RESULT_GUI_SLOT = 4;
    static final int BOOK_GUI_SLOT = 19;
    static final int ITEM_GUI_SLOT = 22;
    static final int GEM_GUI_SLOT = 25;
    private static final List<Integer> LIVE_GUI_SLOTS = List.of(
        RESULT_GUI_SLOT, BOOK_GUI_SLOT, ITEM_GUI_SLOT, GEM_GUI_SLOT
    );

    private final Component originalTitle;
    private final DataSlot state;
    private int displayedState = Integer.MIN_VALUE;

    ImbuingUi(ServerPlayer player, AbstractContainerMenu wrapped, Component title) {
        super(MenuType.GENERIC_9x3, player, wrapped, ImbuingUiAssets.title(title, false));
        validateLayout();
        List<DataSlot> dataSlots = ((AbstractContainerMenuAccessor) wrapped).polymerPatcher$dataSlots();
        if (!IllagerInvasionCompatibility.MENU_CLASS.equals(wrapped.getClass().getName())
            || wrapped.slots.size() != 40 || dataSlots.size() != 1) {
            throw new IllegalArgumentException("Not a compatible Imbuing Table menu");
        }

        // ImbuingMenu order: enchanted book, target item, hallowed gem, result.
        redirect(BOOK_GUI_SLOT, 0);
        redirect(ITEM_GUI_SLOT, 1);
        redirect(GEM_GUI_SLOT, 2);
        redirect(RESULT_GUI_SLOT, 3);
        this.originalTitle = title;
        this.state = dataSlots.getFirst();
        refreshArtwork();
    }

    @Override
    public void onTick() {
        super.onTick();
        refreshArtwork();
    }

    private void refreshArtwork() {
        int next = state.get();
        if (next == displayedState) {
            return;
        }
        displayedState = next;
        // The native screen suppresses its red error marker for ALL_GOOD (0) and ITEM_MISSING (2).
        setTitle(ImbuingUiAssets.title(originalTitle, next != 0 && next != 2));
    }

    static void validateLayout() {
        if (LIVE_GUI_SLOTS.stream().distinct().count() != LIVE_GUI_SLOTS.size()
            || LIVE_GUI_SLOTS.stream().anyMatch(slot -> slot < 0 || slot >= 27)) {
            throw new IllegalStateException("Imbuing Table live slots do not fit a vanilla 9x3 menu");
        }
    }
}
