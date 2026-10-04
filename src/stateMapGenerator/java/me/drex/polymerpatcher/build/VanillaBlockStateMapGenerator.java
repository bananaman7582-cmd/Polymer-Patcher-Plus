package me.drex.polymerpatcher.build;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Generates the semantic state-to-raw-id table from the pristine game on the build classpath. */
public final class VanillaBlockStateMapGenerator {
    private VanillaBlockStateMapGenerator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected output path");
        }

        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        List<String> lines = new ArrayList<>(Block.BLOCK_STATE_REGISTRY.size());
        for (int rawId = 0; rawId < Block.BLOCK_STATE_REGISTRY.size(); rawId++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(rawId);
            if (state == null) {
                throw new IllegalStateException("Missing pristine block state " + rawId);
            }
            lines.add(rawId + "\t" + key(state));
        }

        Path output = Path.of(args[0]);
        Files.createDirectories(output.getParent());
        Files.write(output, lines, StandardCharsets.UTF_8);
        System.out.println("Generated " + lines.size() + " pristine Minecraft 26.2 block-state ids");
    }

    private static String key(BlockState state) {
        StringBuilder key = new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        var values = state.getValues().toList().stream()
            .sorted(Comparator.comparing(value -> value.property().getName()))
            .toList();
        if (!values.isEmpty()) {
            key.append('[');
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    key.append(',');
                }
                var value = values.get(i);
                key.append(value.property().getName()).append('=').append(value.valueName());
            }
            key.append(']');
        }
        return key.toString();
    }
}
