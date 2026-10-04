package me.drex.polymerpatcher.companion.client;

import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A block property whose values are just names.
 * <p>
 * A mod's property is usually an enum of the mod's own, which this client does not have and cannot
 * make. Nothing on the client needs the enum, though: Polymer matches blocks by property and value
 * name, and the block's model file selects its variants by name too. So the values are kept as the
 * names the server gave.
 */
public final class ProxyProperty extends Property<ProxyProperty.Choice> {

    /** One value: its name, and where it comes in the server's list. */
    public record Choice(int index, String name) implements Comparable<Choice> {
        @Override
        public int compareTo(Choice other) {
            return Integer.compare(index, other.index);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final List<Choice> choices;
    private final Map<String, Choice> byName = new HashMap<>();

    private ProxyProperty(String name, List<String> values) {
        super(name, Choice.class);
        List<Choice> list = new ArrayList<>(values.size());
        for (String value : values) {
            Choice choice = new Choice(list.size(), value);
            list.add(choice);
            byName.put(value, choice);
        }
        this.choices = List.copyOf(list);
    }

    @Override
    public List<Choice> getPossibleValues() {
        return choices;
    }

    @Override
    public String getName(Choice value) {
        return value.name();
    }

    @Override
    public Optional<Choice> getValue(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    @Override
    public int getInternalIndex(Choice value) {
        return value.index();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ProxyProperty property && getName().equals(property.getName())
            && choices.equals(property.choices);
    }

    @Override
    public int generateHashCode() {
        return 31 * super.generateHashCode() + choices.hashCode();
    }

    /** The game's own properties, by name and the set of their value names. */
    private static final Map<String, List<Property<?>>> VANILLA = new HashMap<>();

    static {
        for (var field : BlockStateProperties.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !Property.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                Property<?> property = (Property<?>) field.get(null);
                VANILLA.computeIfAbsent(property.getName(), key -> new ArrayList<>()).add(property);
            } catch (IllegalAccessException ignored) {
            }
        }
    }

    /**
     * A property with this name and these values.
     * <p>
     * The game's own property where one matches exactly, because some of the game's code asks after
     * particular properties by identity - waterlogged above all, which is what makes a block hold water.
     */
    public static Property<?> create(String name, List<String> values) {
        Property<?> vanilla = vanillaMatch(name, values);
        if (vanilla != null) {
            return vanilla;
        }
        if (values.size() == 2 && values.contains("true") && values.contains("false")) {
            return BooleanProperty.create(name);
        }
        Integer[] range = contiguousRange(values);
        if (range != null) {
            return IntegerProperty.create(name, range[0], range[1]);
        }
        return new ProxyProperty(name, values);
    }

    private static @Nullable Property<?> vanillaMatch(String name, List<String> values) {
        Set<String> wanted = new HashSet<>(values);
        for (Property<?> candidate : VANILLA.getOrDefault(name, List.of())) {
            Set<String> has = new HashSet<>();
            for (Object value : candidate.getPossibleValues()) {
                has.add(nameOf(candidate, value));
            }
            if (has.equals(wanted)) {
                return candidate;
            }
        }
        return null;
    }

    /** min and max when the values are every whole number between two non-negative ones, else null. */
    private static Integer @Nullable [] contiguousRange(List<String> values) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        Set<Integer> seen = new HashSet<>();
        for (String value : values) {
            int number;
            try {
                number = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return null;
            }
            if (number < 0 || !String.valueOf(number).equals(value)) {
                return null;
            }
            seen.add(number);
            min = Math.min(min, number);
            max = Math.max(max, number);
        }
        if (seen.size() < 2 || max - min + 1 != seen.size()) {
            return null;
        }
        return new Integer[]{min, max};
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static String nameOf(Property property, Object value) {
        return property.getName((Comparable) value);
    }
}
