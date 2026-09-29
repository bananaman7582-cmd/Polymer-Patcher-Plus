package me.drex.polymerpatcher.registry;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.Items;

/** Standalone checks for registry-safe statistic packet filtering. */
public final class ClientSafeStatsCheck {
    private ClientSafeStatsCheck() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        Stat<?> vanilla = Stats.ITEM_USED.get(Items.STONE);
        Object2IntOpenHashMap<Stat<?>> source = new Object2IntOpenHashMap<>();
        source.put(vanilla, 4);

        var safe = ClientSafeStats.sanitize(source, namespace -> false);
        require(safe == source && safe.getInt(vanilla) == 4,
            "an already-safe statistic packet should not be copied");
        require(ClientSafeStats.isAvailable(Identifier.fromNamespaceAndPath("minecraft", "jump"),
                namespace -> false),
            "vanilla statistic identifiers must always be safe");
        require(!ClientSafeStats.isAvailable(Identifier.fromNamespaceAndPath("alexscaves", "test"),
                namespace -> false),
            "a missing mod namespace must not be written to the client");
        require(ClientSafeStats.isAvailable(Identifier.fromNamespaceAndPath("alexscaves", "test"),
                namespace -> namespace.equals("alexscaves")),
            "a matching native client must retain its mod statistic identifiers");

        System.out.println("Client-safe statistic checks passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
