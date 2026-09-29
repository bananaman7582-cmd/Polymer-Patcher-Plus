package me.drex.polymerpatcher.registry;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.stats.Stat;

import java.util.function.Predicate;

/** Filters statistic entries whose type or value is absent from a target client's registries. */
public final class ClientSafeStats {
    private ClientSafeStats() {
    }

    public static Object2IntMap<Stat<?>> sanitize(Object2IntMap<Stat<?>> stats,
                                                  Predicate<String> carriesNamespace) {
        Object2IntOpenHashMap<Stat<?>> safe = new Object2IntOpenHashMap<>(stats.size());
        boolean changed = false;
        for (Object2IntMap.Entry<Stat<?>> entry : stats.object2IntEntrySet()) {
            if (isSafe(entry.getKey(), carriesNamespace)) {
                safe.put(entry.getKey(), entry.getIntValue());
                continue;
            }
            changed = true;
        }
        return changed ? safe : stats;
    }

    static boolean isSafe(Stat<?> stat, Predicate<String> carriesNamespace) {
        Identifier typeId = BuiltInRegistries.STAT_TYPE.getKey(stat.getType());
        if (!isAvailable(typeId, carriesNamespace)) {
            return false;
        }
        return isAvailable(valueId(stat), carriesNamespace);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Identifier valueId(Stat<?> stat) {
        Registry registry = stat.getType().getRegistry();
        return registry.getKey(stat.getValue());
    }

    static boolean isAvailable(Identifier id, Predicate<String> carriesNamespace) {
        return id != null
            && (id.getNamespace().equals("minecraft") || carriesNamespace.test(id.getNamespace()));
    }
}
