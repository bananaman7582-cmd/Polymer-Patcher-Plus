package me.drex.polymerpatcher.compat.alexscaves;

import me.drex.polymerpatcher.util.RedirectedMenuGui;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

import java.lang.reflect.Method;
import java.util.List;

/**
 * A vanilla 9x3 view of Alex's Caves' real Nuclear Furnace menu.
 *
 * <p>The five interactive cells below are the original mod slots, not copied inventories. Their
 * placement changes, but their validation, extraction rules, recipe rewards and block-entity storage
 * remain Alex's Caves' own. The title contains the original screen artwork as a font glyph, exactly as
 * Polymer GUI patches do for other modded menus; unlike a display item it can never be picked up.</p>
 */
final class NuclearFurnaceUi extends RedirectedMenuGui {

    static final int VANILLA_SIZE = 27;

    // Preserve the visual relationships from NuclearFurnaceScreen: waste output/input on the left,
    // smelting input and rod near the centre, cooked output on the right.
    static final int WASTE_OUTPUT_GUI_SLOT = 2;
    static final int INPUT_GUI_SLOT = 3;
    static final int RESULT_GUI_SLOT = 16;
    static final int BARREL_GUI_SLOT = 20;
    static final int ROD_GUI_SLOT = 21;

    private static final List<Integer> LIVE_GUI_SLOTS = List.of(
        WASTE_OUTPUT_GUI_SLOT, INPUT_GUI_SLOT, RESULT_GUI_SLOT, BARREL_GUI_SLOT, ROD_GUI_SLOT
    );

    private final Component originalTitle;
    private final Method wasteScale;
    private final Method cookScale;
    private final Method barrelScale;
    private final Method fissionScale;
    private NuclearFurnaceUiAssets.VisualState visualState;

    NuclearFurnaceUi(ServerPlayer player, AbstractContainerMenu wrapped, Component title) throws ReflectiveOperationException {
        super(MenuType.GENERIC_9x3, player, wrapped,
            NuclearFurnaceUiAssets.title(title, NuclearFurnaceUiAssets.VisualState.EMPTY));
        validateLayout();
        if (!NuclearFurnaceCompat.MENU_CLASS.equals(wrapped.getClass().getName()) || wrapped.slots.size() < 5) {
            throw new IllegalArgumentException("Not a compatible Nuclear Furnace menu");
        }
        // NuclearFurnaceMenu order: input, rod, empty barrel, cooked result, filled waste barrel.
        redirect(INPUT_GUI_SLOT, 0);
        redirect(ROD_GUI_SLOT, 1);
        redirect(BARREL_GUI_SLOT, 2);
        redirect(RESULT_GUI_SLOT, 3);
        redirect(WASTE_OUTPUT_GUI_SLOT, 4);
        this.originalTitle = title;
        Class<?> type = wrapped.getClass();
        this.wasteScale = type.getMethod("getWasteScale");
        this.cookScale = type.getMethod("getCookScale");
        this.barrelScale = type.getMethod("getBarrelScale");
        this.fissionScale = type.getMethod("getFissionScale");
        refreshArtwork();
    }

    @Override
    public void onTick() {
        super.onTick();
        try {
            refreshArtwork();
        } catch (ReflectiveOperationException exception) {
            // The constructor already proved the API exists. If an invocation later fails, keep the
            // functional live menu open with its last good artwork rather than closing it mid-recipe.
        }
    }

    private void refreshArtwork() throws ReflectiveOperationException {
        NuclearFurnaceUiAssets.VisualState next = new NuclearFurnaceUiAssets.VisualState(
            stage(wasteScale, NuclearFurnaceUiAssets.WASTE_STEPS),
            stage(cookScale, NuclearFurnaceUiAssets.COOK_STEPS),
            stage(barrelScale, NuclearFurnaceUiAssets.BARREL_STEPS),
            stage(fissionScale, NuclearFurnaceUiAssets.FISSION_STEPS),
            !wrapped.slots.get(1).hasItem(),
            !wrapped.slots.get(2).hasItem()
        );
        if (!next.equals(visualState)) {
            visualState = next;
            setTitle(NuclearFurnaceUiAssets.title(originalTitle, next));
        }
    }

    private int stage(Method getter, int steps) throws ReflectiveOperationException {
        Object value = getter.invoke(wrapped);
        double scale = value instanceof Number number ? number.doubleValue() : 0.0D;
        return (int) Math.ceil(Math.max(0.0D, Math.min(1.0D, scale)) * steps);
    }

    /** Kept package-visible so the build verifier can catch a future accidental slot collision. */
    static void validateLayout() {
        if (LIVE_GUI_SLOTS.stream().distinct().count() != LIVE_GUI_SLOTS.size()) {
            throw new IllegalStateException("Nuclear Furnace live slots overlap");
        }
        if (LIVE_GUI_SLOTS.stream().anyMatch(slot -> slot < 0 || slot >= VANILLA_SIZE)) {
            throw new IllegalStateException("Nuclear Furnace slot lies outside a 9x3 menu");
        }
    }
}
