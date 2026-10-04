package me.drex.polymerpatcher.companion.shared;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Everything a client needs to register a stand-in for each of a server's modded blocks and fluids.
 * <p>
 * The companion mod registers, while the game starts, a block under the exact name and with the exact
 * properties of every modded block the server has. Polymer's own client side then swaps the vanilla
 * carrier it receives for that block at every position, so the client holds a block with the real
 * shape, light, sounds, hardness and model rather than a borrowed vanilla one. A block can only be
 * registered while the game starts, which is why this is saved to disk and read on the next launch.
 * <p>
 * Shared word for word by the server, which writes it, and the companion, which reads it.
 */
public final class CompanionManifest {

    /** Bumped whenever the layout below changes; a manifest of another format is not read at all. */
    public static final int FORMAT = 1;

    /** The block may only be harvested with the right tool. */
    public static final int BLOCK_REQUIRES_TOOL = 1;
    /** Its shape changes with its surroundings, so the game must not cache it. */
    public static final int BLOCK_DYNAMIC_SHAPE = 1 << 1;
    /** Faces against another of itself are hidden, as glass does. */
    public static final int BLOCK_SKIPS_OWN_FACES = 1 << 2;
    /** Breaking and walking on it raise no particles. */
    public static final int BLOCK_NO_TERRAIN_PARTICLES = 1 << 3;
    /** It is nudged sideways by its position, as flowers are. */
    public static final int BLOCK_OFFSET_XZ = 1 << 4;
    /** It is nudged up and down as well, as small dripleaf is. */
    public static final int BLOCK_OFFSET_XYZ = 1 << 5;
    /** Other blocks may be placed into it, as grass and snow layers are. */
    public static final int BLOCK_REPLACEABLE = 1 << 6;
    /** It hides the faces of its neighbours. */
    public static final int BLOCK_CAN_OCCLUDE = 1 << 7;

    /** It uses its own shape, not a full cube, to block light. */
    public static final int STATE_SHAPE_LIGHT_OCCLUSION = 1;
    /** Skylight passes straight down through it. */
    public static final int STATE_SKYLIGHT = 1 << 1;
    /** The client draws its model; otherwise the server's displays draw it and the block is invisible. */
    public static final int STATE_DRAW_MODEL = 1 << 2;
    public static final int STATE_REDSTONE_CONDUCTOR = 1 << 3;
    public static final int STATE_SUFFOCATING = 1 << 4;
    public static final int STATE_VIEW_BLOCKING = 1 << 5;
    /** Drawn at full brightness whatever the light around it. */
    public static final int STATE_EMISSIVE = 1 << 6;
    /** Counted as solid by the game's older checks (what a torch can stand on and the like). */
    public static final int STATE_SOLID = 1 << 7;

    /** The fluid turns flowing water around a source into a new source, as water does. */
    public static final int FLUID_CONVERTS_TO_SOURCE = 1;
    /** Players are given water movement in it, as the server gives clients without the mod. */
    public static final int FLUID_WATER_PHYSICS = 1 << 1;

    /** How a block is coloured by the biome it stands in. */
    public static final int TINT_NONE = 0;
    public static final int TINT_GRASS = 1;
    public static final int TINT_FOLIAGE = 2;
    public static final int TINT_DRY_FOLIAGE = 3;
    public static final int TINT_WATER = 4;

    /** The sounds a block makes, exactly as its sound type gives them. */
    public record Sound(float volume, float pitch, Identifier breakSound, Identifier stepSound,
                        Identifier placeSound, Identifier hitSound, Identifier fallSound) {
    }

    /** A property by name, with every value it can take, in the order the server lists them. */
    public record PropertyEntry(String name, List<String> values) {
    }

    /**
     * One state of a block.
     *
     * @param values for each of the block's properties, the index of this state's value in it
     * @param outline the shape the cursor outlines, as an index into {@link #shapes}
     */
    public record StateEntry(int[] values, int light, int lightDampening, int flags, int outline,
                             int collision, int visual, int interaction, int occlusion, int sound) {
    }

    public record BlockEntry(Identifier id, List<PropertyEntry> properties, float destroyTime,
                             float explosionResistance, float friction, float speedFactor, float jumpFactor,
                             float maxHorizontalOffset, float maxVerticalOffset, int flags, int tint,
                             List<StateEntry> states) {
    }

    /**
     * A modded fluid, with the block that holds it.
     *
     * @param tint an extra colour laid over the textures, as ARGB, or -1 for none
     */
    public record FluidEntry(Identifier block, Identifier source, Identifier flowing, Identifier stillTexture,
                             Identifier flowingTexture, int tint, int tickDelay, int slopeFindDistance,
                             int dropOff, float explosionResistance, int flags) {
    }

    /** Every distinct shape, each as a run of boxes (minX, minY, minZ, maxX, maxY, maxZ) in block units. */
    public final List<double[]> shapes;
    public final List<Sound> sounds;
    public final List<BlockEntry> blocks;
    public final List<FluidEntry> fluids;

    public CompanionManifest(List<double[]> shapes, List<Sound> sounds, List<BlockEntry> blocks, List<FluidEntry> fluids) {
        this.shapes = shapes;
        this.sounds = sounds;
        this.blocks = blocks;
        this.fluids = fluids;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(FORMAT);
        buf.writeVarInt(shapes.size());
        for (double[] boxes : shapes) {
            buf.writeVarInt(boxes.length);
            for (double value : boxes) {
                buf.writeDouble(value);
            }
        }
        buf.writeVarInt(sounds.size());
        for (Sound sound : sounds) {
            buf.writeFloat(sound.volume());
            buf.writeFloat(sound.pitch());
            buf.writeIdentifier(sound.breakSound());
            buf.writeIdentifier(sound.stepSound());
            buf.writeIdentifier(sound.placeSound());
            buf.writeIdentifier(sound.hitSound());
            buf.writeIdentifier(sound.fallSound());
        }
        buf.writeVarInt(blocks.size());
        for (BlockEntry block : blocks) {
            buf.writeIdentifier(block.id());
            buf.writeVarInt(block.properties().size());
            for (PropertyEntry property : block.properties()) {
                buf.writeUtf(property.name());
                buf.writeVarInt(property.values().size());
                for (String value : property.values()) {
                    buf.writeUtf(value);
                }
            }
            buf.writeFloat(block.destroyTime());
            buf.writeFloat(block.explosionResistance());
            buf.writeFloat(block.friction());
            buf.writeFloat(block.speedFactor());
            buf.writeFloat(block.jumpFactor());
            buf.writeFloat(block.maxHorizontalOffset());
            buf.writeFloat(block.maxVerticalOffset());
            buf.writeVarInt(block.flags());
            buf.writeVarInt(block.tint());
            buf.writeVarInt(block.states().size());
            for (StateEntry state : block.states()) {
                for (int value : state.values()) {
                    buf.writeVarInt(value);
                }
                buf.writeByte(state.light());
                buf.writeByte(state.lightDampening());
                buf.writeVarInt(state.flags());
                buf.writeVarInt(state.outline());
                buf.writeVarInt(state.collision());
                buf.writeVarInt(state.visual());
                buf.writeVarInt(state.interaction());
                buf.writeVarInt(state.occlusion());
                buf.writeVarInt(state.sound());
            }
        }
        buf.writeVarInt(fluids.size());
        for (FluidEntry fluid : fluids) {
            buf.writeIdentifier(fluid.block());
            buf.writeIdentifier(fluid.source());
            buf.writeIdentifier(fluid.flowing());
            buf.writeIdentifier(fluid.stillTexture());
            buf.writeIdentifier(fluid.flowingTexture());
            buf.writeInt(fluid.tint());
            buf.writeVarInt(fluid.tickDelay());
            buf.writeVarInt(fluid.slopeFindDistance());
            buf.writeVarInt(fluid.dropOff());
            buf.writeFloat(fluid.explosionResistance());
            buf.writeVarInt(fluid.flags());
        }
    }

    public static CompanionManifest read(FriendlyByteBuf buf) {
        int format = buf.readVarInt();
        if (format != FORMAT) {
            throw new IllegalStateException("Manifest format " + format + " is not the " + FORMAT + " this reads");
        }
        int shapeCount = buf.readVarInt();
        List<double[]> shapes = new ArrayList<>(shapeCount);
        for (int i = 0; i < shapeCount; i++) {
            double[] boxes = new double[buf.readVarInt()];
            for (int j = 0; j < boxes.length; j++) {
                boxes[j] = buf.readDouble();
            }
            shapes.add(boxes);
        }
        int soundCount = buf.readVarInt();
        List<Sound> sounds = new ArrayList<>(soundCount);
        for (int i = 0; i < soundCount; i++) {
            sounds.add(new Sound(buf.readFloat(), buf.readFloat(), buf.readIdentifier(), buf.readIdentifier(),
                buf.readIdentifier(), buf.readIdentifier(), buf.readIdentifier()));
        }
        int blockCount = buf.readVarInt();
        List<BlockEntry> blocks = new ArrayList<>(blockCount);
        for (int i = 0; i < blockCount; i++) {
            Identifier id = buf.readIdentifier();
            int propertyCount = buf.readVarInt();
            List<PropertyEntry> properties = new ArrayList<>(propertyCount);
            for (int j = 0; j < propertyCount; j++) {
                String name = buf.readUtf();
                int valueCount = buf.readVarInt();
                List<String> values = new ArrayList<>(valueCount);
                for (int k = 0; k < valueCount; k++) {
                    values.add(buf.readUtf());
                }
                properties.add(new PropertyEntry(name, List.copyOf(values)));
            }
            float destroyTime = buf.readFloat();
            float explosionResistance = buf.readFloat();
            float friction = buf.readFloat();
            float speedFactor = buf.readFloat();
            float jumpFactor = buf.readFloat();
            float maxHorizontalOffset = buf.readFloat();
            float maxVerticalOffset = buf.readFloat();
            int flags = buf.readVarInt();
            int tint = buf.readVarInt();
            int stateCount = buf.readVarInt();
            List<StateEntry> states = new ArrayList<>(stateCount);
            for (int j = 0; j < stateCount; j++) {
                int[] values = new int[propertyCount];
                for (int k = 0; k < propertyCount; k++) {
                    values[k] = buf.readVarInt();
                }
                states.add(new StateEntry(values, buf.readUnsignedByte(), buf.readUnsignedByte(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
            }
            blocks.add(new BlockEntry(id, List.copyOf(properties), destroyTime, explosionResistance, friction, speedFactor,
                jumpFactor, maxHorizontalOffset, maxVerticalOffset, flags, tint, List.copyOf(states)));
        }
        int fluidCount = buf.readVarInt();
        List<FluidEntry> fluids = new ArrayList<>(fluidCount);
        for (int i = 0; i < fluidCount; i++) {
            fluids.add(new FluidEntry(buf.readIdentifier(), buf.readIdentifier(), buf.readIdentifier(), buf.readIdentifier(),
                buf.readIdentifier(), buf.readInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readFloat(),
                buf.readVarInt()));
        }
        return new CompanionManifest(List.copyOf(shapes), List.copyOf(sounds), List.copyOf(blocks), List.copyOf(fluids));
    }

    /** The manifest as the compressed bytes that are sent and saved. */
    public byte[] toBytes() {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            write(buf);
            byte[] raw = new byte[buf.readableBytes()];
            buf.readBytes(raw);
            ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 4 + 64);
            try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
                zip.write(raw);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not compress the manifest", e);
        } finally {
            buf.release();
        }
    }

    public static CompanionManifest fromBytes(byte[] compressed) throws IOException {
        byte[] raw;
        try (GZIPInputStream zip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            raw = zip.readAllBytes();
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(raw));
        try {
            return read(buf);
        } finally {
            buf.release();
        }
    }

    /** What a manifest is known by: a hash of its compressed bytes, which both sides can work out. */
    public static String hashOf(byte[] compressed) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(compressed)).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
