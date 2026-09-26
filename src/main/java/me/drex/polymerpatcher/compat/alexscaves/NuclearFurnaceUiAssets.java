package me.drex.polymerpatcher.compat.alexscaves;

import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.resources.ResourceHelper;
import me.drex.polymerpatcher.util.MenuUiArtwork;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.packs.resources.IoSupplier;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Resource-pack artwork used to make the vanilla chest screen look like the real furnace screen. */
final class NuclearFurnaceUiAssets {
    private static final String SOURCE = "textures/gui/nuclear_furnace.png";
    private static final int SCREEN_WIDTH = 176;
    private static final int SCREEN_HEIGHT = 166;
    static final int WASTE_STEPS = 13;
    static final int COOK_STEPS = 12;
    static final int BARREL_STEPS = 7;
    static final int FISSION_STEPS = 7;
    private static final int FIRST_GLYPH = 0xE400;
    private static final char REWIND = '\uE3F0';
    private static final char RESTORE = '\uE3F1';
    private static final char ROD_EMPTY = '\uE500';
    private static final char BARREL_EMPTY = '\uE501';

    private NuclearFurnaceUiAssets() {
    }

    static void generate(ResourcePackBuilder builder) {
        IoSupplier<InputStream> source = ResourceHelper.getAsset("alexscaves", SOURCE);
        if (source == null) {
            PolymerPatcher.LOGGER.warn("Could not find Alex's Caves' Nuclear Furnace screen artwork");
            return;
        }

        try (InputStream stream = source.get()) {
            // The source sheet also stores progress-strip sprites to the right of the 176x166 screen.
            // A title glyph would otherwise paint those loose sprite strips beside the menu. Keep a
            // 256x256 canvas for exact 1:1 bitmap-font scale, but copy only the real screen rectangle.
            BufferedImage sourceImage = ImageIO.read(stream);
            if (sourceImage == null || sourceImage.getWidth() < 176 || sourceImage.getHeight() < 166) {
                throw new IllegalStateException("Nuclear Furnace screen artwork is not 176x166");
            }
            BufferedImage screen = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = screen.createGraphics();
            try {
                graphics.setComposite(AlphaComposite.Src);
                graphics.drawImage(sourceImage, 0, 0, 176, 166, 0, 0, 176, 166, null);
            } finally {
                graphics.dispose();
            }
            relocateSlots(screen, sourceImage);
            MenuUiArtwork.write(builder, "nuclear_furnace", screen);
            writeFrames(builder, "nuclear_furnace_waste", WASTE_STEPS,
                stage -> wasteFrame(sourceImage, stage));
            writeFrames(builder, "nuclear_furnace_cook", COOK_STEPS,
                stage -> cookFrame(sourceImage, stage));
            writeFrames(builder, "nuclear_furnace_barrel", BARREL_STEPS,
                stage -> barrelFrame(sourceImage, stage));
            writeFrames(builder, "nuclear_furnace_fission", FISSION_STEPS,
                stage -> fissionFrame(sourceImage, stage));
            writeEmptyIcons(builder, sourceImage);
        } catch (Throwable throwable) {
            PolymerPatcher.LOGGER.error("Could not build the Nuclear Furnace screen artwork", throwable);
        }
    }

    static Component title(Component original, VisualState state) {
        MutableComponent result = MenuUiArtwork.artwork("nuclear_furnace");
        result.append(overlay("nuclear_furnace_waste", FIRST_GLYPH + state.waste()));
        result.append(overlay("nuclear_furnace_cook", FIRST_GLYPH + state.cook()));
        result.append(overlay("nuclear_furnace_barrel", FIRST_GLYPH + state.barrel()));
        result.append(overlay("nuclear_furnace_fission", FIRST_GLYPH + state.fission()));
        if (state.rodEmpty()) {
            result.append(overlay("nuclear_furnace_icons", ROD_EMPTY));
        }
        if (state.barrelEmpty()) {
            result.append(overlay("nuclear_furnace_icons", BARREL_EMPTY));
        }
        return result.append(MenuUiArtwork.defaultTitle(original));
    }

    private static Component overlay(String asset, int glyph) {
        Style style = Style.EMPTY.withColor(0xFFFFFF).withoutShadow()
            .withFont(new FontDescription.Resource(PolymerPatcher.id(asset)));
        // The backdrop leaves the cursor at x=81 while its pixels begin at x=-8. Draw one
        // transparent 176px overlay at that origin, then put the cursor back exactly where it was.
        return Component.literal("" + REWIND + (char) glyph + RESTORE).withStyle(style);
    }

    /**
     * Moves the painted slot frames onto the only positions a vanilla 9x3 menu can put its real slots.
     * The source menu is custom and uses arbitrary x coordinates; SGui deliberately exposes a vanilla
     * menu whose five furnace slots are 7px left/right of that artwork. The inventory is one pixel lower.
     */
    static void relocateSlots(BufferedImage screen, BufferedImage source) {
        List<SlotMove> moves = new ArrayList<>();
        moves.add(new SlotMove(36, 16, 43, 17, 18, 18));
        moves.add(new SlotMove(66, 16, 61, 17, 18, 18));
        // Unlike every other slot, Alex's Caves paints a 26x26 highlighted result frame.
        moves.add(new SlotMove(122, 30, 129, 31, 26, 26));
        moves.add(new SlotMove(36, 52, 43, 53, 18, 18));
        moves.add(new SlotMove(66, 52, 61, 53, 18, 18));
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                int x = 7 + column * 18;
                moves.add(new SlotMove(x, 83 + row * 18, x, 84 + row * 18, 18, 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            int x = 7 + column * 18;
            moves.add(new SlotMove(x, 141, x, 142, 18, 18));
        }

        List<BufferedImage> frames = moves.stream()
            .map(move -> source.getSubimage(move.fromX(), move.fromY(), move.width(), move.height()))
            .toList();
        Graphics2D graphics = screen.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setColor(new Color(source.getRGB(100, 10), true));
            for (SlotMove move : moves) {
                graphics.fillRect(move.fromX(), move.fromY(), move.width(), move.height());
            }
            for (int index = 0; index < moves.size(); index++) {
                SlotMove move = moves.get(index);
                graphics.drawImage(frames.get(index), move.toX(), move.toY(), null);
            }
        } finally {
            graphics.dispose();
        }
    }

    static BufferedImage wasteFrame(BufferedImage source, int stage) {
        BufferedImage frame = emptyFrame();
        int height = scaled(52, stage, WASTE_STEPS);
        if (height > 0) {
            draw(source, frame, 176, 32 + 52 - height, 16, height,
                13, 17 + 52 - height);
        }
        return frame;
    }

    static BufferedImage cookFrame(BufferedImage source, int stage) {
        BufferedImage frame = emptyFrame();
        int width = scaled(24, stage, COOK_STEPS);
        if (width > 0) {
            draw(source, frame, 176, 14, width, 17, 90, 35);
        }
        return frame;
    }

    static BufferedImage barrelFrame(BufferedImage source, int stage) {
        BufferedImage frame = emptyFrame();
        int height = scaled(14, stage, BARREL_STEPS);
        if (height > 0) {
            draw(source, frame, 192, 14 + 14 - height, 15, height,
                45, 37 + 14 - height);
        }
        return frame;
    }

    static BufferedImage fissionFrame(BufferedImage source, int stage) {
        BufferedImage frame = emptyFrame();
        int height = scaled(14, stage, FISSION_STEPS);
        if (height > 0) {
            draw(source, frame, 176, 14 + 14 - height, 14, height,
                63, 37 + 14 - height);
        }
        return frame;
    }

    private static int scaled(int pixels, int stage, int steps) {
        int bounded = Math.max(0, Math.min(steps, stage));
        return (int) Math.ceil(pixels * (bounded / (double) steps));
    }

    private static BufferedImage emptyFrame() {
        return new BufferedImage(SCREEN_WIDTH, SCREEN_HEIGHT, BufferedImage.TYPE_INT_ARGB);
    }

    private static void draw(BufferedImage source, BufferedImage target,
                             int sx, int sy, int width, int height, int dx, int dy) {
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(source, dx, dy, dx + width, dy + height,
                sx, sy, sx + width, sy + height, null);
        } finally {
            graphics.dispose();
        }
    }

    private static void writeFrames(ResourcePackBuilder builder, String asset, int steps,
                                    FrameFactory factory) throws Exception {
        int count = steps + 1;
        BufferedImage sheet = new BufferedImage(SCREEN_WIDTH, SCREEN_HEIGHT * count, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = sheet.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            for (int stage = 0; stage < count; stage++) {
                graphics.drawImage(factory.create(stage), 0, stage * SCREEN_HEIGHT, null);
            }
        } finally {
            graphics.dispose();
        }
        writeOverlayFont(builder, asset, sheet, count, FIRST_GLYPH);
    }

    private static void writeEmptyIcons(ResourcePackBuilder builder, BufferedImage source) throws Exception {
        BufferedImage sheet = new BufferedImage(SCREEN_WIDTH, SCREEN_HEIGHT * 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = sheet.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            // The real screen paints these hints only while their slots are empty.
            graphics.drawImage(source, 62, 54, 78, 70, 176, 84, 192, 100, null);
            graphics.drawImage(source, 44, SCREEN_HEIGHT + 54, 60, SCREEN_HEIGHT + 70,
                192, 84, 208, 100, null);
        } finally {
            graphics.dispose();
        }
        writeOverlayFont(builder, "nuclear_furnace_icons", sheet, 2, ROD_EMPTY);
    }

    private static void writeOverlayFont(ResourcePackBuilder builder, String asset, BufferedImage sheet,
                                         int rows, int firstGlyph) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(sheet, "png", output)) {
                throw new IllegalStateException("No PNG writer is available");
            }
            builder.addData("assets/polymer-patcher/textures/gui/" + asset + ".png", output.toByteArray());
        }

        StringBuilder chars = new StringBuilder();
        for (int row = 0; row < rows; row++) {
            if (row > 0) {
                chars.append(',');
            }
            chars.append('"').append((char) (firstGlyph + row)).append('"');
        }
        String font = """
            {
              "providers": [
                {"type":"space","advances":{"\\uE3F0":-89,"\\uE3F1":-88}},
                {"type":"bitmap","file":"polymer-patcher:gui/%s.png","ascent":13,"height":166,"chars":[%s]}
              ]
            }
            """.formatted(asset, chars);
        builder.addData("assets/polymer-patcher/font/" + asset + ".json",
            font.getBytes(StandardCharsets.UTF_8));
    }

    record VisualState(int waste, int cook, int barrel, int fission,
                       boolean rodEmpty, boolean barrelEmpty) {
        static final VisualState EMPTY = new VisualState(0, 0, 0, 0, true, true);
    }

    @FunctionalInterface
    private interface FrameFactory {
        BufferedImage create(int stage);
    }

    private record SlotMove(int fromX, int fromY, int toX, int toY, int width, int height) {
    }
}
