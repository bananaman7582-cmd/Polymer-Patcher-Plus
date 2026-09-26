package me.drex.polymerpatcher.client;

import net.fabricmc.loader.impl.lib.mappingio.tree.MappingTree;
import net.fabricmc.loader.impl.lib.tinyremapper.*;
import net.fabricmc.loader.impl.util.log.LogCategory;
import net.fabricmc.loader.impl.util.log.TinyRemapperLoggerAdapter;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

public class JarDeobfuscationHelper {
    public static void deobfuscate0(List<Path> inputFiles, List<Path> outputFiles, List<Path> tmpFiles,
                                     MappingTree mappings, String sourceNamespace, String targetNamespace) throws IOException {
        TinyRemapper remapper = TinyRemapper.newRemapper(new TinyRemapperLoggerAdapter(LogCategory.GAME_REMAP))
            .withMappings(TinyUtils.createMappingProvider(mappings, sourceNamespace, targetNamespace))
            .rebuildSourceFilenames(true)
            .build();

        Set<Path> depPaths = new HashSet<>();

        List<OutputConsumerPath> outputConsumers = new ArrayList<>(inputFiles.size());
        List<InputTag> inputTags = new ArrayList<>(inputFiles.size());

        try {
            for (int i = 0; i < inputFiles.size(); i++) {
                Path inputFile = inputFiles.get(i);
                Path tmpFile = tmpFiles.get(i);

                InputTag inputTag = remapper.createInputTag();
                OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(tmpFile)
                    // force jar despite the .tmp extension
                    .assumeArchive(true)
                    .build();

                outputConsumers.add(outputConsumer);
                inputTags.add(inputTag);

                outputConsumer.addNonClassFiles(inputFile, NonClassCopyMode.FIX_META_INF, remapper);
                remapper.readInputsAsync(inputTag, inputFile);
            }

            for (int i = 0; i < inputFiles.size(); i++) {
                remapper.apply(outputConsumers.get(i), inputTags.get(i));
            }
        } finally {
            for (OutputConsumerPath outputConsumer : outputConsumers) {
                outputConsumer.close();
            }

            remapper.finish();
        }

        // Minecraft doesn't tend to check if a ZipFileSystem is already present,
        // so we clean up here.

        depPaths.addAll(tmpFiles);

        for (Path p : depPaths) {
            try {
                p.getFileSystem().close();
            } catch (Exception e) {
                // pass
            }

            try {
                FileSystems.getFileSystem(new URI("jar:" + p.toUri())).close();
            } catch (Exception e) {
                // pass
            }
        }

        List<Path> missing = new ArrayList<>();

        for (int i = 0; i < inputFiles.size(); i++) {
            Path inputFile = inputFiles.get(i);
            Path tmpFile = tmpFiles.get(i);
            Path outputFile = outputFiles.get(i);

            boolean found;

            try (JarFile jar = new JarFile(tmpFile.toFile())) {
                found = jar.stream().anyMatch((e) -> e.getName().endsWith(".class"));
            }

            if (!found) {
                missing.add(inputFile);
                Files.delete(tmpFile);
            } else {
                Files.move(tmpFile, outputFile);
            }
        }

        if (!missing.isEmpty()) {
            throw new RuntimeException("Generated deobfuscated JARs contain no classes: "+missing);
        }
    }
}
