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
        // The replacement is installed before the original menu's initMenu call. Its DataSlots are
        // therefore never attached or forwarded to the generic client, which makes server-only gauges
        // safe to ignore. A custom menu button is different: the client has no control capable of
        // producing that interaction, so button-driven screens remain outside automatic conversion.
        int dataSlotCount = ((AbstractContainerMenuAccessor) menu).polymerPatcher$dataSlots().size();
        boolean buttonDriven;
        try {
            buttonDriven = menu.getClass()
                .getMethod("clickMenuButton", net.minecraft.world.entity.player.Player.class, int.class)
                .getDeclaringClass() != AbstractContainerMenu.class;
        } catch (ReflectiveOperationException exception) {
            return Optional.empty();
        }
        if (!AutomaticMenuLayout.canSafelyBridgeControlState(dataSlotCount, buttonDriven)) {
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
