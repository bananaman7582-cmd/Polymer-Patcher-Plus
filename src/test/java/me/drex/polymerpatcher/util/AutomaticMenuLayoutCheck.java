package me.drex.polymerpatcher.util;

import java.util.ArrayList;
import java.util.List;

/** Standalone verifier for the safety boundary of automatic mod-menu replacement. */
public final class AutomaticMenuLayoutCheck {
    private AutomaticMenuLayoutCheck() {
    }

    public static void main(String[] args) {
        List<AutomaticMenuLayout.SlotPoint> valid = playerSlots(3);
        valid.add(point(36, 0, false, 8, 18));
        valid.add(point(37, 1, false, 44, 36));
        AutomaticMenuLayout.Layout layout = AutomaticMenuLayout.analyze(valid)
            .orElseThrow(() -> new AssertionError("ordinary 9x3 geometry was rejected"));
        require(layout.rows() == 3, "wrong row count");
        require(layout.redirectedSlots().get(0) == 36, "top-left slot was not redirected");
        require(layout.redirectedSlots().get(11) == 37, "second-row slot was not redirected");

        List<AutomaticMenuLayout.SlotPoint> tolerance = playerSlots(2);
        tolerance.add(point(36, 0, false, 10, 20));
        require(AutomaticMenuLayout.analyze(tolerance).isPresent(), "two-pixel source offset was rejected");

        List<AutomaticMenuLayout.SlotPoint> collision = playerSlots(3);
        collision.add(point(36, 0, false, 8, 18));
        collision.add(point(37, 1, false, 9, 17));
        require(AutomaticMenuLayout.analyze(collision).isEmpty(), "overlapping click targets were accepted");

        List<AutomaticMenuLayout.SlotPoint> arbitrary = playerSlots(3);
        arbitrary.add(point(36, 0, false, 27, 33));
        require(AutomaticMenuLayout.analyze(arbitrary).isEmpty(), "arbitrary custom slot was accepted");

        List<AutomaticMenuLayout.SlotPoint> incompleteInventory = playerSlots(3);
        incompleteInventory.remove(0);
        incompleteInventory.add(point(36, 0, false, 8, 18));
        require(AutomaticMenuLayout.analyze(incompleteInventory).isEmpty(), "incomplete player inventory was accepted");

        List<AutomaticMenuLayout.SlotPoint> fake = playerSlots(3);
        fake.add(new AutomaticMenuLayout.SlotPoint(36, 0, false, true, true, 8, 18));
        require(AutomaticMenuLayout.analyze(fake).isEmpty(), "ghost slot was accepted");

        require(AutomaticMenuUiAssets.candidates("example_menu").contains("textures/gui/example.png"),
            "menu suffix source discovery regressed");
        require(AutomaticMenuLayout.canRepresentControlState(0, false),
            "plain slot-only containers were rejected");
        require(!AutomaticMenuLayout.canRepresentControlState(1, false),
            "a stateful machine was accepted as a generic container");
        require(!AutomaticMenuLayout.canRepresentControlState(0, true),
            "a button-driven machine was accepted as a generic container");
        System.out.println("Automatic menu layout checks passed");
    }

    private static List<AutomaticMenuLayout.SlotPoint> playerSlots(int rows) {
        List<AutomaticMenuLayout.SlotPoint> result = new ArrayList<>();
        int mainTop = 31 + rows * 18;
        int menuIndex = 0;
        for (int index = 9; index < 36; index++) {
            int column = (index - 9) % 9;
            int row = (index - 9) / 9;
            result.add(new AutomaticMenuLayout.SlotPoint(menuIndex++, index, true, false, true,
                8 + column * 18, mainTop + row * 18));
        }
        for (int index = 0; index < 9; index++) {
            result.add(new AutomaticMenuLayout.SlotPoint(menuIndex++, index, true, false, true,
                8 + index * 18, mainTop + 58));
        }
        return result;
    }

    private static AutomaticMenuLayout.SlotPoint point(int menu, int container, boolean player, int x, int y) {
        return new AutomaticMenuLayout.SlotPoint(menu, container, player, false, true, x, y);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
