package me.drex.polymerpatcher.dump;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Loads the rendering classes a mod marked as client-only, on a server that refuses to load them.
 * <p>
 * Fabric strips by environment: a class annotated {@code @Environment(EnvType.CLIENT)} is not merely
 * absent on a server, it is actively refused - {@code FabricTransformer} throws rather than hand the
 * bytes over. Nearly every mod annotates its renderers and models that way, Alex's Mobs included, and
 * this mod's whole purpose is to read exactly those classes and draw them for a vanilla client.
 * <p>
 * So the bytes are read out of the mod's own jar and handed straight to the class loader, which is
 * the one step of the normal path that does no environment checking. Nothing is bypassed beyond that
 * check: the classes still load into the same loader as everything else, still link against the same
 * game classes, and still see whatever mixins were applied to them.
 * <p>
 * Only ever asked for classes named by the dump, which are the renderers the client itself drew with.
 */
public final class ClientOnlyClasses {
    /**
     * What the loader says when it refuses a class, and where the name sits inside it.
     */
    private static final String REFUSAL = "Cannot load class ";
    private static final String REFUSAL_TAIL = " in environment type";

    /**
     * A class can rest on others that are refused for the same reason - a renderer on its model, that
     * model on its base class - and each attempt reports only the first. The cap is what stops a
     * genuine loop from turning into a hang; no real chain comes near it.
     */
    private static final int MAX_ROUNDS = 64;

    private ClientOnlyClasses() {
    }

    /**
     * Something to change a class by as it is defined, or null to define it as it came.
     * <p>
     * A class the game refuses is a class this mod defines itself, and the one thing that cannot be
     * done to a refused class is patch it the ordinary way - the patcher never sees it, because the
     * refusal happens first. It can be changed here instead, which is the only door it comes through.
     * <p>
     * Set by the mod proper, which is where the reading of bytecode lives; this half only knows to ask.
     */
    public static volatile java.util.function.BiFunction<String, byte[], byte[]> patches;

    private static byte[] patched(String name, byte[] bytes) {
        var change = patches;
        if (change == null) {
            return bytes;
        }

        try {
            byte[] changed = change.apply(name, bytes);
            return changed == null ? bytes : changed;
        } catch (Throwable t) {
            PolymerPatcherDumper.LOGGER.debug("Could not change {} on the way in", name, t);
            return bytes;
        }
    }

    /** Whether the classes of split-environment mods have already been brought in. */
    private static boolean predefined;

    /**
     * The attribute a mod built with split source sets uses to list its client-side classes.
     */
    private static final String CLIENT_ONLY_ENTRIES = "Fabric-Loom-Client-Only-Entries";

    /**
     * Brings in the client classes of every mod built with split source sets, before anything asks for
     * one.
     * <p>
     * Defining a refused class after the fact and trying again works while the failure is only a
     * failure. It stops working the moment the refusal happens inside a class's own static set-up:
     * <b>a class whose initialiser has thrown once is finished for the life of the server</b>, and every
     * later attempt is answered from that first failure without the code running again. Retrying cannot
     * reach it, and this is not a rare shape - it is the ordinary way a mod declares its model layers.
     * <p>
     * Enderscape declares its layers that way. The first renderer built asked for a model, the model was
     * refused, and the declaration holding all five model layers died with it - so the drifter, the
     * driftlet, the rubblemite and both rustles had nothing to draw, and no later attempt could have
     * fixed it. The retry loop was working exactly as written; it was simply too late by the time it ran.
     * <p>
     * So the classes are brought in first. Which ones is not guessed at: a mod built this way lists them
     * itself, in its own manifest, because that is how it tells the loader what to leave out.
     */
    public static void predefineSplitEnvironment() {
        if (predefined) {
            return;
        }
        predefined = true;

        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String entries = clientOnlyEntries(mod);
            if (entries == null || entries.isEmpty()) {
                continue;
            }

            int brought = 0;
            int notNeeded = 0;
            for (String entry : entries.split(";")) {
                String path = entry.trim();
                if (!path.endsWith(".class") || isNotWorthBringingIn(path)) {
                    continue;
                }

                String name = path.substring(0, path.length() - ".class".length()).replace('/', '.');
                if (loadQuietly(name) != null) {
                    brought++;
                } else {
                    notNeeded++;
                }
            }

            // Counted rather than complained about one by one. Plenty of a mod's client classes cannot
            // stand up on a server at all - a settings screen, a lightmap - and none of them are wanted
            // here; only the ones a renderer reaches for matter. Reporting each as a failure with its own
            // stack buried the start-up under ninety traces for something that was working
            PolymerPatcherDumper.LOGGER.info(
                "{} keeps its client code apart from the rest; brought in {} class(es) so its renderers can be built{}",
                mod.getMetadata().getId(), brought,
                notNeeded == 0 ? "" : " (" + notNeeded + " others do not run on a server and are not needed)");
        }
    }

    /** The manifest attribute listing a mod's client classes, or null when it is not built that way. */
    private static String clientOnlyEntries(ModContainer mod) {
        Optional<Path> manifest = mod.findPath("META-INF/MANIFEST.MF");
        if (manifest.isEmpty()) {
            return null;
        }

        try (java.io.InputStream stream = Files.newInputStream(manifest.get())) {
            return new java.util.jar.Manifest(stream).getMainAttributes().getValue(CLIENT_ONLY_ENTRIES);
        } catch (Throwable t) {
            PolymerPatcherDumper.LOGGER.debug("Could not read the manifest of {}", mod.getMetadata().getId(), t);
            return null;
        }
    }

    /**
     * Classes there is no point bringing in, and every reason not to try.
     * <p>
     * A mixin is not a class to load at all - it is a description of a change to somebody else's class,
     * and asking for it by name gets a complaint rather than anything useful. Screens, menus and data
     * generation are client and build-time furniture that nothing here draws with.
     */
    private static boolean isNotWorthBringingIn(String path) {
        return path.contains("/mixin/")
            || path.contains("/datagen/")
            || path.contains("/gui/")
            || path.contains("/screen")
            || path.contains("/config/");
    }

    /**
     * @return the class, or null when it is genuinely absent rather than merely refused
     */
    public static Class<?> load(@NotNull String name) {
        return load(name, false);
    }

    /** As {@link #load}, but silent about a class that will not come - the caller is only trying. */
    public static Class<?> loadQuietly(@NotNull String name) {
        return load(name, true);
    }

    private static Class<?> load(@NotNull String name, boolean quiet) {
        for (int round = 0; round < MAX_ROUNDS; round++) {
            try {
                return Class.forName(name, false, ClientOnlyClasses.class.getClassLoader());
            } catch (ClassNotFoundException e) {
                // The mod that owned it is not installed any more, which the caller treats as missing
                return null;
            } catch (Throwable t) {
                String refused = refusedClass(t);
                if (refused == null || !define(refused, quiet, new HashSet<>())) {
                    if (quiet) {
                        PolymerPatcherDumper.LOGGER.debug("Could not load {} on this side", name, t);
                    } else {
                        PolymerPatcherDumper.LOGGER.warn("Could not load {} on this side", name, t);
                    }
                    return null;
                }
            }
        }

        if (quiet) {
            PolymerPatcherDumper.LOGGER.debug("Gave up loading {}; too many client-only classes beneath it", name);
        } else {
            PolymerPatcherDumper.LOGGER.warn("Gave up loading {}; too many client-only classes beneath it", name);
        }
        return null;
    }

    /**
     * How many times in a row it is worth defining a refused class and trying again. Shared with
     * callers that run their own retry loop, so a renderer whose constructor reaches for one
     * client-only class after another is given the same allowance as a class being loaded by name.
     */
    public static int maxRounds() {
        return MAX_ROUNDS;
    }

    /**
     * Whether this failure is the loader refusing a client-only class, rather than something that
     * retrying will not help with.
     */
    public static boolean namesRefusedClass(Throwable thrown) {
        return refusedClass(thrown) != null;
    }

    /**
     * Defines whatever client-only class this failure was refused, so the caller can try again.
     * <p>
     * The refusal does not only happen when a class is loaded by name: a renderer's constructor builds
     * its model, and the model's own base class is refused halfway through, which surfaces as this
     * same message wrapped in whatever the constructor threw. The answer is the same either way.
     *
     * @return whether something was defined and another attempt is worth making
     */
    public static boolean defineRefused(Throwable thrown) {
        String refused = refusedClass(thrown);
        return refused != null && define(refused, true, new HashSet<>());
    }

    /**
     * The class named in a refusal, or null if this was some other failure entirely.
     */
    private static String refusedClass(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            // Looked for anywhere in the message rather than only at the start. A refusal reached
            // through a lambda arrives wrapped: the loader's complaint becomes the text of a
            // BootstrapMethodError, which becomes the text of an ExceptionInInitializerError, and by
            // then the sentence is quoted inside another rather than beginning it. Enderscape's model
            // layers are declared with lambdas and failed exactly that way, so every refusal underneath
            // them went unrecognised and no retry was ever made
            String message = t.getMessage();
            if (message == null) continue;

            int start = message.indexOf(REFUSAL);
            if (start < 0) continue;

            int from = start + REFUSAL.length();
            int end = message.indexOf(REFUSAL_TAIL, from);
            if (end > from) {
                return message.substring(from, end).trim();
            }
        }

        return null;
    }

    /**
     * Reads a class out of whichever mod carries it and defines it directly.
     */
    private static boolean define(@NotNull String name, boolean quiet, Set<String> defining) {
        if (!defining.add(name)) {
            return false;
        }

        byte[] bytes = read(name);
        if (bytes == null) {
            defining.remove(name);
            return false;
        }

        byte[] using = patched(name, bytes);

        try {
            ClassLoader loader = ClientOnlyClasses.class.getClassLoader();
            Method defineClassFwd = loader.getClass().getMethod(
                "defineClassFwd", String.class, byte[].class, int.class, int.class, java.security.CodeSource.class
            );
            defineClassFwd.setAccessible(true);
            for (int round = 0; round < MAX_ROUNDS; round++) {
                try {
                    defineClassFwd.invoke(loader, name, using, 0, using.length, null);
                    return true;
                } catch (Throwable t) {
                    String dependency = refusedClass(t);

                    // A class this mod changed on the way in and the game will not take. Whatever the
                    // change was worth, it is not worth the class: the original is defined instead and
                    // everything that rests on it carries on working
                    if (dependency == null && using != bytes) {
                        PolymerPatcherDumper.LOGGER.warn("The change made to {} on the way in was refused; "
                            + "defining it as it came", name, t);
                        using = bytes;
                        continue;
                    }

                    if (dependency == null || dependency.equals(name)
                        || !define(dependency, quiet, defining)) {
                        if (quiet) {
                            PolymerPatcherDumper.LOGGER.debug("Could not define client-only class {}", name, t);
                        } else {
                            PolymerPatcherDumper.LOGGER.warn("Could not define client-only class {}", name, t);
                        }
                        return false;
                    }
                }
            }

            if (quiet) {
                PolymerPatcherDumper.LOGGER.debug("Gave up defining {}; too many client-only classes beneath it", name);
            } else {
                PolymerPatcherDumper.LOGGER.warn("Gave up defining {}; too many client-only classes beneath it", name);
            }
            return false;
        } catch (Throwable t) {
            if (quiet) {
                PolymerPatcherDumper.LOGGER.debug("Could not prepare client-only class {}", name, t);
            } else {
                PolymerPatcherDumper.LOGGER.warn("Could not prepare client-only class {}", name, t);
            }
            return false;
        } finally {
            defining.remove(name);
        }
    }

    private static byte[] read(@NotNull String name) {
        String path = name.replace('.', '/') + ".class";

        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            Optional<Path> file = mod.findPath(path);
            if (file.isEmpty()) continue;

            try {
                return Files.readAllBytes(file.get());
            } catch (Throwable t) {
                PolymerPatcherDumper.LOGGER.warn("Could not read {} out of {}", path, mod.getMetadata().getId(), t);
            }
        }

        return null;
    }
}
