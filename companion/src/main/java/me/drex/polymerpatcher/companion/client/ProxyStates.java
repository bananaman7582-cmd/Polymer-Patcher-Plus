package me.drex.polymerpatcher.companion.client;

import me.drex.polymerpatcher.companion.shared.CompanionManifest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the server said about each state of one block, ready for the block to answer questions from.
 */
final class ProxyStates {

    /** A state's description with its shapes and sounds already built. */
    record Resolved(CompanionManifest.StateEntry entry, VoxelShape outline, VoxelShape collision, VoxelShape visual,
                    VoxelShape interaction, VoxelShape occlusion, SoundType sound) {
        boolean has(int flag) {
            return (entry.flags() & flag) != 0;
        }
    }

    /** The shapes and sounds of one manifest, built once and shared by all its blocks. */
    static final class Tables {
        final List<VoxelShape> shapes;
        final List<SoundType> sounds;

        Tables(CompanionManifest manifest) {
            List<VoxelShape> builtShapes = new ArrayList<>(manifest.shapes.size());
            for (double[] boxes : manifest.shapes) {
                builtShapes.add(shapeOf(boxes));
            }
            this.shapes = List.copyOf(builtShapes);
            List<SoundType> builtSounds = new ArrayList<>(manifest.sounds.size());
            for (CompanionManifest.Sound sound : manifest.sounds) {
                builtSounds.add(new SoundType(sound.volume(), sound.pitch(), event(sound.breakSound()), event(sound.stepSound()),
                    event(sound.placeSound()), event(sound.hitSound()), event(sound.fallSound())));
            }
            this.sounds = List.copyOf(builtSounds);
        }

        VoxelShape shape(int index) {
            return index >= 0 && index < shapes.size() ? shapes.get(index) : Shapes.block();
        }

        SoundType sound(int index) {
            return index >= 0 && index < sounds.size() ? sounds.get(index) : SoundType.STONE;
        }

        private static VoxelShape shapeOf(double[] boxes) {
            if (boxes.length == 0) {
                return Shapes.empty();
            }
            if (boxes.length == 6 && boxes[0] == 0 && boxes[1] == 0 && boxes[2] == 0 && boxes[3] == 1 && boxes[4] == 1 && boxes[5] == 1) {
                return Shapes.block();
            }
            VoxelShape shape = Shapes.empty();
            for (int i = 0; i + 5 < boxes.length; i += 6) {
                shape = Shapes.or(shape, Shapes.box(boxes[i], boxes[i + 1], boxes[i + 2], boxes[i + 3], boxes[i + 4], boxes[i + 5]));
            }
            return shape.optimize();
        }

        /** The game's own sound where it has one by that name, so its sound list stays one list. */
        private static SoundEvent event(Identifier id) {
            return BuiltInRegistries.SOUND_EVENT.getOptional(id).orElseGet(() -> SoundEvent.createVariableRangeEvent(id));
        }
    }

    final CompanionManifest.BlockEntry entry;
    final Tables tables;
    final List<Property<?>> properties;
    private final Map<String, CompanionManifest.StateEntry> byKey = new HashMap<>();
    private final Map<BlockState, Resolved> resolved = new IdentityHashMap<>();
    private Resolved fallback;

    ProxyStates(CompanionManifest.BlockEntry entry, Tables tables) {
        this.entry = entry;
        this.tables = tables;
        List<Property<?>> created = new ArrayList<>(entry.properties().size());
        for (CompanionManifest.PropertyEntry property : entry.properties()) {
            created.add(ProxyProperty.create(property.name(), property.values()));
        }
        this.properties = List.copyOf(created);
        for (CompanionManifest.StateEntry state : entry.states()) {
            StringBuilder key = new StringBuilder();
            for (int i = 0; i < state.values().length; i++) {
                key.append(entry.properties().get(i).values().get(state.values()[i])).append(',');
            }
            byKey.put(key.toString(), state);
        }
    }

    /** Called once the block exists, to settle every one of its states. */
    void bind(Block block) {
        CompanionManifest.StateEntry first = entry.states().isEmpty() ? null : entry.states().getFirst();
        int missing = 0;
        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            CompanionManifest.StateEntry found = byKey.get(keyOf(state));
            if (found == null) {
                missing++;
                found = first;
            }
            if (found != null) {
                resolved.put(state, resolve(found));
            }
        }
        if (first != null) {
            fallback = resolve(first);
        }
        if (missing > 0) {
            CompanionMod.LOGGER.warn("{} state(s) of {} were not described by the server; they borrow its first state", missing, entry.id());
        }
    }

    private Resolved resolve(CompanionManifest.StateEntry state) {
        return new Resolved(state, tables.shape(state.outline()), tables.shape(state.collision()), tables.shape(state.visual()),
            tables.shape(state.interaction()), tables.shape(state.occlusion()), tables.sound(state.sound()));
    }

    /** The settled description of a state; asked constantly, so a lookup by identity. */
    Resolved of(BlockState state) {
        Resolved found = resolved.get(state);
        if (found != null) {
            return found;
        }
        // Only while the block is still being built, before bind
        CompanionManifest.StateEntry entry = byKey.get(keyOf(state));
        if (entry != null) {
            return resolve(entry);
        }
        return fallback != null ? fallback : resolve(new CompanionManifest.StateEntry(new int[0], 0, 0,
            CompanionManifest.STATE_DRAW_MODEL, 1, 1, 1, 0, 1, 0));
    }

    private String keyOf(BlockState state) {
        StringBuilder key = new StringBuilder();
        for (Property<?> property : properties) {
            key.append(ProxyProperty.nameOf(property, state.getValue(property))).append(',');
        }
        return key.toString();
    }
}
