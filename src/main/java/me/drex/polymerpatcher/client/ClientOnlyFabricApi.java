package me.drex.polymerpatcher.client;

import me.drex.polymerpatcher.PolymerPatcher;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Puts Fabric API's client-only halves back on the server's class path.
 * <p>
 * This mod runs real client renderers on a server, and the game's own client classes are there to be
 * run - but Fabric API is not one jar, it is thirty-odd, and the ones marked as client code are simply
 * not loaded when the server starts. A mod whose renderer reaches for one of them does not fail
 * politely: the class its renderer needs cannot be found, the static set-up that was reaching for it
 * dies, and <b>once a class has failed to initialise it stays failed</b>. Every renderer that touches it
 * afterwards gets the same answer without the code being run again.
 * <p>
 * That is what happened to Enderscape. Its model layers are declared through
 * {@code ModelLayerRegistry}, which lives in {@code fabric-rendering-v1} - a module whose own
 * description says it is client-only, so the server never loaded it. The first renderer to ask brought
 * the whole class down, and the drifter, the driftlet, the rubblemite and both rustles were left with no
 * model at all: a red placeholder box each.
 * <p>
 * The classes themselves are perfectly happy on a server. They are in the jar, sitting inside Fabric
 * API, and nothing about them is client-specific beyond the label on the module holding them. So the
 * modules are unpacked and handed to the class path, and the renderers find what they were looking for.
 * <p>
 * Nothing is done on a client, where the real modules are already loaded and would be loaded twice.
 */
public final class ClientOnlyFabricApi {

    /**
     * A class from a client-only module. Finding it means the modules are already present - this is a
     * client, or a later version of Fabric API shipped them to servers - and there is nothing to do.
     */
    private static final String PROBE = "net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry";

    private static boolean done;

    private ClientOnlyFabricApi() {
    }

    public static void ensureLoaded() {
        if (done) {
            return;
        }
        done = true;

        if (present(PROBE)) {
            return;
        }

        ModContainer fabricApi = FabricLoader.getInstance().getModContainer("fabric-api").orElse(null);
        if (fabricApi == null) {
            PolymerPatcher.LOGGER.warn("Fabric API is not installed, so its client-only modules cannot be added; mods whose renderers use them will show placeholders");
            return;
        }

        Optional<Path> jars = fabricApi.findPath("META-INF/jars");
        if (jars.isEmpty()) {
            return;
        }

        Path cache = FabricLoader.getInstance().getGameDir().resolve(".polymer-patcher").resolve("client-fabric-api");
        List<String> added = new ArrayList<>();

        try (Stream<Path> contents = Files.list(jars.get())) {
            Files.createDirectories(cache);

            for (Path nested : contents.toList()) {
                String name = nested.getFileName().toString();
                if (!name.endsWith(".jar") || !isClientOnly(nested)) {
                    continue;
                }

                try {
                    // Copied out first: the class path wants a jar it can open on its own, and this one
                    // is currently a file inside another file
                    Path target = cache.resolve(name);
                    Files.copy(nested, target, StandardCopyOption.REPLACE_EXISTING);
                    emptyOut(target, MODEL_LAYER_REGISTRY);
                    FabricLauncherBase.getLauncher().addToClassPath(target);
                    added.add(name);
                } catch (Throwable e) {
                    PolymerPatcher.LOGGER.warn("Could not add {} to the class path", name, e);
                }
            }
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.warn("Could not read Fabric API's bundled modules; mods whose renderers use its client-only parts will show placeholders", e);
            return;
        }

        if (added.isEmpty()) {
            PolymerPatcher.LOGGER.info("Found no client-only Fabric API modules to add");
        } else {
            PolymerPatcher.LOGGER.info("Added {} client-only Fabric API module(s) so modded renderers can be built: {}", added.size(), added);
        }
    }

    /**
     * Fabric's own registry of model layers, which is no use here and will not run.
     * <p>
     * Adding the modules got the class found, and the next thing it did was throw
     * {@code AssertionError: This should not occur!}. The registry is real code, but it reaches the
     * game's layer list through a Mixin accessor, and a mixin only exists once its config has been
     * applied - which for a client-only module on a server never happens. The stub body is what is left,
     * and it does nothing but throw.
     * <p>
     * Nothing here needs it to work. Layer definitions come from the dump taken on a client, not from
     * this registry, and a mod only touches it to announce layers it is about to use. So the announcing
     * is allowed to do nothing, and the declaration that was making it finishes - which is all that was
     * ever wanted, since it is the same static set-up that hands the renderer the names of its layers.
     */
    private static final String MODEL_LAYER_REGISTRY = "net/fabricmc/fabric/api/client/rendering/v1/ModelLayerRegistry.class";

    /**
     * Rewrites one class in a copied jar so its methods return without doing anything.
     * <p>
     * Only what is written to our own copy in the cache; the module inside Fabric API is untouched, and
     * a client never gets here at all.
     */
    private static void emptyOut(Path jar, String classFile) throws java.io.IOException {
        Path rewritten = jar.resolveSibling(jar.getFileName() + ".rewriting");
        boolean found = false;

        try (var in = new java.util.zip.ZipInputStream(Files.newInputStream(jar));
             var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(rewritten))) {
            for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                byte[] bytes = in.readAllBytes();
                if (entry.getName().equals(classFile)) {
                    bytes = withEmptyMethods(bytes);
                    found = true;
                }
                out.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                out.write(bytes);
                out.closeEntry();
            }
        }

        if (found) {
            Files.move(rewritten, jar, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.deleteIfExists(rewritten);
        }
    }

    /** The same class with every method that returns nothing left empty. */
    private static byte[] withEmptyMethods(byte[] bytes) {
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(bytes).accept(node, 0);

        for (org.objectweb.asm.tree.MethodNode method : node.methods) {
            // The constructor is left alone: emptying it would leave the object never built
            if (method.name.equals("<init>") || !method.desc.endsWith(")V")) {
                continue;
            }

            method.instructions.clear();
            method.tryCatchBlocks.clear();
            method.localVariables = null;
            method.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        }

        org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(
            org.objectweb.asm.ClassWriter.COMPUTE_MAXS | org.objectweb.asm.ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Whether a module says it is client code, read out of its own description. */
    private static boolean isClientOnly(Path jar) {
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(Files.newInputStream(jar))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.getName().equals("fabric.mod.json")) {
                    continue;
                }

                String json = new String(readAll(zip), StandardCharsets.UTF_8);
                return json.replace(" ", "").contains("\"environment\":\"client\"");
            }
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Could not read {}", jar, e);
        }
        return false;
    }

    private static byte[] readAll(InputStream stream) throws java.io.IOException {
        return stream.readAllBytes();
    }

    private static boolean present(String name) {
        try {
            Class.forName(name, false, ClientOnlyFabricApi.class.getClassLoader());
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
