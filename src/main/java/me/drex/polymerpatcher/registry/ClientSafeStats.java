package me.drex.polymerpatcher.registry;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import me.drex.polymerpatcher.resources.ResourceHelper;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.stats.Stat;

import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Filters statistic entries whose type or value is absent from a target client's registries. */
public final class ClientSafeStats {
    private ClientSafeStats() {
    }

    public static Object2IntMap<Stat<?>> sanitize(Object2IntMap<Stat<?>> stats,
                                                  Predicate<String> carriesNamespace) {
        return sanitize(stats, carriesNamespace, ClientSafeStats::isVanillaValue);
    }

    static Object2IntMap<Stat<?>> sanitize(Object2IntMap<Stat<?>> stats,
                                           Predicate<String> carriesNamespace,
                                           BiPredicate<Registry<?>, Identifier> vanillaValue) {
        Object2IntOpenHashMap<Stat<?>> safe = new Object2IntOpenHashMap<>(stats.size());
        boolean changed = false;
        for (Object2IntMap.Entry<Stat<?>> entry : stats.object2IntEntrySet()) {
            if (isSafe(entry.getKey(), carriesNamespace, vanillaValue)) {
                safe.put(entry.getKey(), entry.getIntValue());
                continue;
            }
            changed = true;
        }
        return changed ? safe : stats;
    }

    static boolean isSafe(Stat<?> stat, Predicate<String> carriesNamespace) {
        return isSafe(stat, carriesNamespace, ClientSafeStats::isVanillaValue);
    }

    static boolean isSafe(Stat<?> stat, Predicate<String> carriesNamespace,
                          BiPredicate<Registry<?>, Identifier> vanillaValue) {
        Identifier typeId = BuiltInRegistries.STAT_TYPE.getKey(stat.getType());
        if (!isAvailable(BuiltInRegistries.STAT_TYPE, typeId, carriesNamespace, vanillaValue)) {
            return false;
        }
        return isAvailable(stat.getType().getRegistry(), valueId(stat), carriesNamespace, vanillaValue);
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

    /**
     * Whether a registry value can be encoded with the numbering a particular client owns.
     *
     * <p>The namespace is not sufficient. Backports are allowed to register new blocks and items
     * under {@code minecraft:*}; those entries are still appended to the server registry and their
     * numeric ids do not exist on a stock client. Statistics encode their value through the owning
     * registry's raw id, so allowing one of those entries makes the entire award-stats packet
     * undecodable. Ask the pristine client assets for registry types that have an asset instead.</p>
     */
    static boolean isAvailable(Registry<?> registry, Identifier id,
                               Predicate<String> carriesNamespace) {
        return isAvailable(registry, id, carriesNamespace, ClientSafeStats::isVanillaValue);
    }

    static boolean isAvailable(Registry<?> registry, Identifier id,
                               Predicate<String> carriesNamespace,
                               BiPredicate<Registry<?>, Identifier> vanillaValue) {
        if (id == null) {
            return false;
        }
        if (!id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {
            return carriesNamespace.test(id.getNamespace());
        }
        return vanillaValue.test(registry, id);
    }

    private static boolean isVanillaValue(Registry<?> registry, Identifier id) {
        if (registry.key() == BuiltInRegistries.ITEM.key()) {
            return ResourceHelper.hasVanillaAsset(id.getNamespace(), "items/" + id.getPath() + ".json");
        }
        if (registry.key() == BuiltInRegistries.BLOCK.key()) {
            return ResourceHelper.hasVanillaAsset(id.getNamespace(), "blockstates/" + id.getPath() + ".json");
        }
        if (registry.key() == BuiltInRegistries.ENTITY_TYPE.key()) {
            return RegistryPatcher.isVanillaEntityType(id);
        }
        if (registry.key() == BuiltInRegistries.CUSTOM_STAT.key()) {
            return !ResourceHelper.hasVanillaLang()
                || ResourceHelper.hasVanillaLangKey("stat.minecraft." + id.getPath());
        }
        // The stat-type registry itself is fixed by the game. Other unusual stat registries have no
        // asset catalogue to compare, so retaining the game's own entries is the safest fallback.
        return true;
    }
}
