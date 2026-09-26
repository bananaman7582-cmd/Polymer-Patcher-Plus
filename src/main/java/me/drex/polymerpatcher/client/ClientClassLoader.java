package me.drex.polymerpatcher.client;

import me.drex.polymerpatcher.PolymerPatcher;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.launch.MappingConfiguration;
import net.fabricmc.loader.impl.lib.mappingio.tree.MappingTree;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ClientClassLoader {
    public static void init() throws IOException, ClassNotFoundException, NoSuchFieldException, IllegalAccessException {
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) return;
        // https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
        // https://piston-meta.mojang.com/v1/packages/<hash>/26.2.json
        // https://piston-data.mojang.com/v1/objects/2dc72797acbc1b63fc16a11c4ac393605f453754/client.jar
        Path cache = FabricLoader.getInstance().getGameDir().resolve(".polymer-patch/");

        Files.createDirectories(cache);

        FabricLauncher launcher = FabricLauncherBase.getLauncher();
        MappingConfiguration mappingConfiguration = launcher.getMappingConfiguration();

        String sourceNamespace = MappingConfiguration.OFFICIAL_NAMESPACE;
        String targetNamespace = mappingConfiguration.getRuntimeNamespace();

        String officialJarFilename = String.format("%s-%s.jar", "client", sourceNamespace);
        Path inputFile = cache.resolve(officialJarFilename);

        if (!Files.exists(inputFile)) {
            PolymerPatcher.LOGGER.info("Downloading client jar...");
            URL url = new URL("https://piston-data.mojang.com/v1/objects/2dc72797acbc1b63fc16a11c4ac393605f453754/client.jar");
            URLConnection connection = url.openConnection();
            InputStream is = connection.getInputStream();
            Files.copy(is, inputFile);
        }


        // 26.2 ships unobfuscated: Mojang publishes no mappings for it and Fabric's intermediary stopped
        // at 1.21.11, so the jar just downloaded already carries the same names the server is running
        // under. There is nothing to translate, and asking for a translation would only send the
        // remapper looking for namespaces that no longer exist.
        if (!mappingConfiguration.hasAnyMappings() || sourceNamespace.equals(targetNamespace)) {
            PolymerPatcher.LOGGER.info("Adding the client jar as it is; this version of Minecraft is not obfuscated");
            launcher.addToClassPath(inputFile);
            return;
        }

        String deobfJarFilename = String.format("%s-%s.jar", "client", targetNamespace);
        Path outputFile = cache.resolve(deobfJarFilename);
        Path tmpFile = cache.resolve(deobfJarFilename + ".tmp");

        MappingTree mappings = mappingConfiguration.getMappings();

        if (!Files.exists(outputFile)) {
            PolymerPatcher.LOGGER.info("Deobfuscating client jar...");
            JarDeobfuscationHelper.deobfuscate0(List.of(inputFile), List.of(outputFile), List.of(tmpFile), mappings, sourceNamespace, targetNamespace);
        }

        launcher.addToClassPath(outputFile);
    }
}
