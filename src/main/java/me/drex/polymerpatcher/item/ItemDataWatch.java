package me.drex.polymerpatcher.item;

import me.drex.polymerpatcher.mixin.item.CompoundTagAccessor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Watches which pieces of an item's own data a renderer reads while it draws it.
 * <p>
 * An item that moves in the hand - a bow being drawn, a gauntlet charging, a crank turning - is moved by
 * a number the mod keeps on the item itself, because that is the only way the movement can reach anybody
 * else's screen. The renderer reads that number and poses the model from it. Which number it is has no
 * name a server can look up: it is whatever key the mod chose, and every mod chooses its own.
 * <p>
 * So the renderer is asked. While it draws, the tag it reads from is swapped for one that writes down
 * every key it is asked for, and what comes back is the list of things this item's appearance depends on
 * - found without knowing anything about the mod, and without a line of it needing to be written here.
 * Setting one of those keys and drawing again is then a way of asking "and what does it look like after
 * being held for four ticks?", which is the whole of {@link HeldItemProbe}'s animation measuring.
 *
 * @see HeldItemProbe
 */
public final class ItemDataWatch {

    private ItemDataWatch() {
    }

    /**
     * Read before anything else on the path every item's data is read through. False everywhere but
     * inside a measurement, which happens while a resource pack is being built and at no other time.
     */
    public static volatile boolean watching;

    private static final ThreadLocal<Set<String>> ASKED = new ThreadLocal<>();

    /**
     * Collects what a renderer asks for while the given work runs.
     *
     * @return the keys it read, in the order it first asked for them
     */
    public static Set<String> around(Runnable work) {
        Set<String> asked = new LinkedHashSet<>();
        ASKED.set(asked);
        watching = true;
        try {
            work.run();
        } finally {
            ASKED.remove();
            watching = false;
        }
        return asked;
    }

    /**
     * Called from the patch on the path item data is read through, and hands back a tag that says what
     * it was asked for. The original where nothing is watching, which is every other moment.
     */
    public static @Nullable CompoundTag record(@Nullable CompoundTag tag) {
        Set<String> asked = ASKED.get();
        if (tag == null || asked == null) {
            return tag;
        }

        try {
            CompoundTag copy = tag.copy();
            CompoundTagAccessor inside = (CompoundTagAccessor) (Object) copy;
            inside.polymerPatcher$setContents(new Asked(inside.polymerPatcher$contents(), asked));
            return copy;
        } catch (Throwable ignored) {
            // Nothing here is worth failing a render over; the item simply keeps whatever was already
            // worked out for it
            return tag;
        }
    }

    /**
     * The tag's own contents, which answer every read and write down what they were asked for.
     * <p>
     * Sat underneath the tag rather than in front of it, because the tag is final and every one of its
     * two dozen ways of reading a value goes straight to this map. One map covers all of them.
     */
    private static final class Asked implements Map<String, Tag> {

        private final Map<String, Tag> contents;
        private final Set<String> asked;

        private Asked(Map<String, Tag> contents, Set<String> asked) {
            this.contents = contents;
            this.asked = asked;
        }

        @Override
        public Tag get(Object key) {
            if (key instanceof String name) {
                asked.add(name);
            }
            return contents.get(key);
        }

        @Override
        public boolean containsKey(Object key) {
            if (key instanceof String name) {
                asked.add(name);
            }
            return contents.containsKey(key);
        }

        @Override
        public int size() {
            return contents.size();
        }

        @Override
        public boolean isEmpty() {
            return contents.isEmpty();
        }

        @Override
        public boolean containsValue(Object value) {
            return contents.containsValue(value);
        }

        @Override
        public Tag put(String key, Tag value) {
            return contents.put(key, value);
        }

        @Override
        public Tag remove(Object key) {
            return contents.remove(key);
        }

        @Override
        public void putAll(Map<? extends String, ? extends Tag> other) {
            contents.putAll(other);
        }

        @Override
        public void clear() {
            contents.clear();
        }

        @Override
        public Set<String> keySet() {
            return contents.keySet();
        }

        @Override
        public Collection<Tag> values() {
            return contents.values();
        }

        @Override
        public Set<Entry<String, Tag>> entrySet() {
            return contents.entrySet();
        }
    }
}
