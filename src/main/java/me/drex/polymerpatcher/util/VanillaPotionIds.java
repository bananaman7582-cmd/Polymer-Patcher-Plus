package me.drex.polymerpatcher.util;

import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Pristine potion wire numbers, independent of mod registration order on the server. */
public final class VanillaPotionIds {
    private static final Map<Identifier, Integer> IDS = load();
    private static final java.util.Set<Identifier> REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private VanillaPotionIds() {}

    public static int id(Object value) {
        if (!(value instanceof Holder<?> holder)) return -1;
        return holder.unwrapKey().map(key -> {
            int id = IDS.getOrDefault(key.identifier(), -1);
            if (id >= 0 && holder.isBound() && holder.value() instanceof net.minecraft.world.item.alchemy.Potion potion) {
                int serverId = net.minecraft.core.registries.BuiltInRegistries.POTION.getId(potion);
                if (serverId >= 0 && serverId != id && REPORTED.add(key.identifier())) {
                    me.drex.polymerpatcher.PolymerPatcher.LOGGER.info(
                        "Potion {} has server id {} but pristine client id {}; its item packets use the client id",
                        key.identifier(), serverId, id);
                }
            }
            return id;
        }).orElse(-1);
    }

    private static Map<Identifier, Integer> load() {
        var stream = VanillaPotionIds.class.getResourceAsStream("/assets/polymer-patcher/vanilla_potion_ids_26_2.tsv");
        if (stream == null) throw new IllegalStateException("Missing pristine potion registry map");
        Map<Identifier, Integer> ids = new HashMap<>();
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                String[] fields = line.split("\t", 2);
                ids.put(Identifier.parse(fields[1]), Integer.parseInt(fields[0]));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read pristine potion registry map", e);
        }
        return Map.copyOf(ids);
    }
}
