package me.drex.polymerpatcher.util;

import me.drex.polymerpatcher.mixin.sync.AbstractContainerMenuAccessor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Conservative, mod-independent bridge for custom menus that are already chest-grid shaped. */
final class AutomaticMenuUi extends RedirectedMenuGui {
    private AutomaticMenuUi(ServerPlayer player, AbstractContainerMenu wrapped, Identifier menuId,
                            AutomaticMenuLayout.Layout layout, net.minecraft.network.chat.Component title) {
        super(type(layout.rows()), player, wrapped, AutomaticMenuUiAssets.title(menuId, layout.rows(), title));
        layout.redirectedSlots().forEach(this::redirect);
    }

    static Optional<AutomaticMenuUi> create(ServerPlayer player, AbstractContainerMenu menu,
                                            Identifier menuId, net.minecraft.network.chat.Component title) {
        // Generic chest menus have no client-side data slots. A mod menu with even one DataSlot is a
        // stateful machine, not a plain inventory, even when its slot coordinates happen to line up
        // with a 9-wide grid. Forwarding one of its property updates to a generic menu makes an
        // unmodified client index an empty data-slot list and disconnect with a protocol error.
        int dataSlotCount = ((AbstractContainerMenuAccessor) menu).polymerPatcher$dataSlots().size();
        boolean buttonDriven;
        try {
            buttonDriven = menu.getClass()
                .getMethod("clickMenuButton", net.minecraft.world.entity.player.Player.class, int.class)
                .getDeclaringClass() != AbstractContainerMenu.class;
        } catch (ReflectiveOperationException exception) {
            return Optional.empty();
        }
        // A button-driven screen cannot be represented by slots alone either. Reject both kinds of
        // machine state even when their slot geometry happens to resemble a chest.
        if (!AutomaticMenuLayout.canRepresentControlState(dataSlotCount, buttonDriven)) {
            return Optional.empty();
        }

        List<AutomaticMenuLayout.SlotPoint> points = new ArrayList<>(menu.slots.size());
        for (int index = 0; index < menu.slots.size(); index++) {
            Slot slot = menu.slots.get(index);
            boolean inventory = slot.container == player.getInventory();
            points.add(new AutomaticMenuLayout.SlotPoint(index, slot.getContainerSlot(), inventory,
                slot.isFake(), slot.isActive(), slot.x, slot.y));
        }
        return AutomaticMenuLayout.analyze(points)
            .map(layout -> new AutomaticMenuUi(player, menu, menuId, layout, title));
    }

    private static MenuType<?> type(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            case 6 -> MenuType.GENERIC_9x6;
            default -> throw new IllegalArgumentException("Unsupported generic menu height " + rows);
        };
    }
}
