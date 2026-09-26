package me.drex.polymerpatcher.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Pure slot-geometry analysis used by the automatic vanilla menu bridge and its build verifier. */
public final class AutomaticMenuLayout {
    private static final int TOLERANCE = 2;

    private AutomaticMenuLayout() {
    }

    public record SlotPoint(int menuIndex, int containerIndex, boolean playerInventory, boolean fake,
                            boolean active, int x, int y) {
    }

    public record Layout(int rows, Map<Integer, Integer> redirectedSlots) {
        public Layout {
            redirectedSlots = Map.copyOf(redirectedSlots);
        }
    }

    /** A generic container has neither property indices nor menu-button protocol of its own. */
    public static boolean canRepresentControlState(int dataSlotCount, boolean buttonDriven) {
        return dataSlotCount == 0 && !buttonDriven;
    }

    /**
     * Accepts only layouts a vanilla 9-wide container can describe without lying about a click target.
     */
    public static Optional<Layout> analyze(List<SlotPoint> slots) {
        List<SlotPoint> player = new ArrayList<>();
        List<SlotPoint> machine = new ArrayList<>();
        for (SlotPoint slot : slots) {
            (slot.playerInventory() ? player : machine).add(slot);
        }

        if (machine.isEmpty() || machine.size() > 54 || player.size() != 36) {
            return Optional.empty();
        }

        Map<Integer, SlotPoint> playerByIndex = new HashMap<>();
        for (SlotPoint slot : player) {
            if (slot.fake() || !slot.active() || slot.containerIndex() < 0 || slot.containerIndex() > 35
                || playerByIndex.put(slot.containerIndex(), slot) != null) {
                return Optional.empty();
            }
        }
        if (playerByIndex.size() != 36) {
            return Optional.empty();
        }

        // Vanilla generic containers put the first main-inventory row at 31 + 18 * menuRows.
        // Validate every player slot, not merely the top-left one: a custom screen with a coincidental
        // height must not pass while the client and server disagree about the remaining 35 targets.
        int mainTop = playerByIndex.get(9).y();
        int rows = Math.round((mainTop - 31) / 18.0F);
        if (rows < 1 || rows > 6 || !near(mainTop, 31 + rows * 18)) {
            return Optional.empty();
        }
        for (int index = 9; index < 36; index++) {
            int column = (index - 9) % 9;
            int row = (index - 9) / 9;
            if (!at(playerByIndex.get(index), 8 + column * 18, mainTop + row * 18)) {
                return Optional.empty();
            }
        }
        int hotbarY = mainTop + 58;
        for (int index = 0; index < 9; index++) {
            if (!at(playerByIndex.get(index), 8 + index * 18, hotbarY)) {
                return Optional.empty();
            }
        }

        Map<Integer, Integer> redirects = new HashMap<>();
        Set<Integer> occupied = new HashSet<>();
        for (SlotPoint slot : machine) {
            if (slot.fake() || !slot.active()) {
                return Optional.empty();
            }
            int column = Math.round((slot.x() - 8) / 18.0F);
            int row = Math.round((slot.y() - 18) / 18.0F);
            if (column < 0 || column >= 9 || row < 0 || row >= rows
                || !at(slot, 8 + column * 18, 18 + row * 18)) {
                return Optional.empty();
            }
            int guiSlot = row * 9 + column;
            if (!occupied.add(guiSlot)) {
                return Optional.empty();
            }
            redirects.put(guiSlot, slot.menuIndex());
        }
        return Optional.of(new Layout(rows, redirects));
    }

    private static boolean at(SlotPoint slot, int x, int y) {
        return near(slot.x(), x) && near(slot.y(), y);
    }

    private static boolean near(int actual, int expected) {
        return Math.abs(actual - expected) <= TOLERANCE;
    }
}
