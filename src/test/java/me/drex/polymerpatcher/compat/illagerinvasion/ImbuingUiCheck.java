package me.drex.polymerpatcher.compat.illagerinvasion;

/** Standalone contract check for the four live Imbuing Table slots. */
public final class ImbuingUiCheck {
    private ImbuingUiCheck() {
    }

    public static void main(String[] args) {
        ImbuingUi.validateLayout();
        if (!IllagerInvasionCompatibility.MENU_ID.toString().equals("illagerinvasion:imbuing")) {
            throw new AssertionError("Imbuing menu registry id changed");
        }
        System.out.println("Imbuing Table vanilla UI slot contract verified");
    }
}
