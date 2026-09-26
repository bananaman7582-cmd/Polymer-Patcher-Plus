package me.drex.polymerpatcher.compat.alexscaves;

import java.awt.Color;
import java.awt.image.BufferedImage;

/** Standalone build check for the vanilla Nuclear Furnace's fixed 9x3 slot contract. */
public final class NuclearFurnaceUiCheck {

    private NuclearFurnaceUiCheck() {
    }

    public static void main(String[] args) {
        NuclearFurnaceUi.validateLayout();
        if (NuclearFurnaceUi.VANILLA_SIZE != 27) {
            throw new AssertionError("The replacement no longer matches MenuType.GENERIC_9x3");
        }
        BufferedImage source = new BufferedImage(176, 166, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                source.setRGB(x, y, new Color(198, 198, 198).getRGB());
            }
        }
        source.setRGB(36, 16, Color.RED.getRGB());
        source.setRGB(122, 30, Color.MAGENTA.getRGB());
        source.setRGB(147, 55, Color.ORANGE.getRGB());
        source.setRGB(7, 83, Color.BLUE.getRGB());
        BufferedImage screen = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        var graphics = screen.createGraphics();
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        NuclearFurnaceUiAssets.relocateSlots(screen, source);
        require(screen.getRGB(43, 17) == Color.RED.getRGB(), "special slot artwork did not follow its real slot");
        require(screen.getRGB(129, 31) == Color.MAGENTA.getRGB(), "result frame's top-left did not move");
        require(screen.getRGB(154, 56) == Color.ORANGE.getRGB(), "result frame was cropped to an ordinary slot");
        require(screen.getRGB(7, 84) == Color.BLUE.getRGB(), "inventory artwork did not move down one pixel");

        BufferedImage sheet = new BufferedImage(208, 166, BufferedImage.TYPE_INT_ARGB);
        sheet.setRGB(176, 14, Color.GREEN.getRGB());
        sheet.setRGB(199, 30, Color.YELLOW.getRGB());
        sheet.setRGB(176, 83, Color.CYAN.getRGB());
        sheet.setRGB(192, 27, Color.PINK.getRGB());
        BufferedImage cook = NuclearFurnaceUiAssets.cookFrame(sheet, NuclearFurnaceUiAssets.COOK_STEPS);
        require(cook.getRGB(90, 35) == Color.GREEN.getRGB(), "cooking arrow did not start at its live position");
        require(cook.getRGB(113, 51) == Color.YELLOW.getRGB(), "full cooking arrow was cropped");
        BufferedImage waste = NuclearFurnaceUiAssets.wasteFrame(sheet, NuclearFurnaceUiAssets.WASTE_STEPS);
        require(waste.getRGB(13, 68) == Color.CYAN.getRGB(), "waste gauge did not reach its bottom pixel");
        BufferedImage barrel = NuclearFurnaceUiAssets.barrelFrame(sheet, NuclearFurnaceUiAssets.BARREL_STEPS);
        require(barrel.getRGB(45, 50) == Color.PINK.getRGB(), "barrel gauge was not relocated with its slot");
        System.out.println("Nuclear Furnace UI slot contract verified");
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
