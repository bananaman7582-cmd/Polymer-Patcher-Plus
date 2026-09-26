package me.drex.polymerpatcher.entity;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.resources.ResourceHelper;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the other skins a mob can wear, beyond the one it happened to be wearing when the dump was
 * taken.
 * <p>
 * A mob's pieces are filed under the texture it is drawn with, so a texture nothing was generated for
 * is a mob with no pieces to show - it arrives untextured. The dump records the textures it saw, and it
 * only ever sees one mob of each kind: whichever colour that one was. Alex's Mobs lobsters come in six,
 * catfish in three sizes, flying fish in three, and only the recorded one was ever generated. That is
 * why a red lobster looked right while a blue one did not, and why every large catfish was untextured -
 * not randomness, just which variant the dump happened to catch.
 * <p>
 * The mob's own name is what ties its variants together: {@code lobster} owns {@code lobster_red} and
 * {@code lobster_blue}, {@code catfish} owns {@code catfish_large}. So every texture sitting beside the
 * recorded one whose name is the mob's, or begins with the mob's name and an underscore, is taken as
 * another skin of the same mob. Being beside it matters as much as the name: it keeps
 * {@code flying_fish_boots}, which lives a directory deeper among the equipment, from being mistaken
 * for a flying fish.
 */
public final class EntityTextures {

    private static final String TEXTURES = "textures/";
    private static final String PNG = ".png";

    /** Every texture each mod ships, read once. */
    private static volatile Map<String, List<String>> index;

    private EntityTextures() {
    }

    /**
     * The one texture that should stand in for the mob, out of everything it was seen drawing with.
     * <p>
     * A renderer usually draws a mob in several passes - a body, a glowing overlay, a pair of eyes -
     * and the dump records all of them with nothing to say which was which. Every caller here wrote a
     * model for each and then kept whichever came last, which is a coin toss, and it kept losing:
     * a ferrouslime was drawn with {@code ferrouslime_eyes} and came out as a small blob of eyes with
     * no slime around them, and a submarine picked whichever damage state happened to sort last.
     * <p>
     * The mob's own name is the answer. A renderer's main texture is named after the thing it draws -
     * {@code entity/ferrouslime} - and its extra passes are that name with something on the end. So an
     * exact match wins, then the nearest thing to one, and only then does order decide.
     */
    public static Identifier primary(Identifier entityId, Collection<Identifier> textures) {
        Identifier best = null;
        int bestScore = Integer.MAX_VALUE;
        String name = entityId.getPath();

        for (Identifier texture : textures) {
            String leaf = leafOf(texture);
            int score;
            if (leaf.equals(name)) {
                score = 0;
            } else if (leaf.startsWith(name)) {
                // Nearest to the bare name: gummy_bear_red beats gummy_bear_red_glowing
                score = 1 + (leaf.length() - name.length());
            } else {
                // Nothing in the name to go on, so the plainest one - an overlay is named for what it
                // adds, so it is nearly always the longer
                score = 1_000 + leaf.length();
            }

            if (score < bestScore) {
                bestScore = score;
                best = texture;
            }
        }

        return best;
    }

    private static String leafOf(Identifier texture) {
        String path = texture.getPath();
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /**
     * The recorded textures plus every sibling that belongs to the same mob.
     */
    public static Set<Identifier> expand(Identifier entityId, Set<Identifier> recorded) {
        // The recorded ones always come first and are never dropped; this only ever adds
        Set<Identifier> all = new LinkedHashSet<>(recorded);

        for (Identifier texture : recorded) {
            all.addAll(siblings(entityId, texture));
        }

        if (all.size() > recorded.size()) {
            PolymerPatcher.LOGGER.debug("Found {} skin(s) for {} where the dump recorded {}", all.size(), entityId, recorded.size());
        }

        return all;
    }

    /**
     * Textures that look like they belong to this entity, for one the dump recorded none for.
     * <p>
     * A dump only records what a client was actually seen drawing, and a renderer that names its
     * texture from inside its own draw call - rather than through the method the dump watches - is
     * recorded with none at all. Two dozen of Alex's Caves' entities come through that way, and an
     * entity with no texture is passed over entirely: no model is written for it and nothing is drawn.
     * <p>
     * The mod's own assets are a good enough answer. An entity called {@code underzealot} whose mod
     * ships {@code textures/entity/underzealot.png} is not a guess worth agonising over - and being
     * wrong costs a mob the wrong skin, where doing nothing costs it any appearance at all.
     */
    public static Set<Identifier> guess(Identifier entityId) {
        String name = entityId.getPath();
        Set<Identifier> found = new LinkedHashSet<>();

        for (String file : index().getOrDefault(entityId.getNamespace(), List.of())) {
            String bare = file.substring(TEXTURES.length(), file.length() - PNG.length());
            int slash = bare.lastIndexOf('/');
            String leaf = slash < 0 ? bare : bare.substring(slash + 1);

            // Under entity/, because that is where a mob's skin lives and a block or an item sharing
            // its name is not the same picture
            if (!bare.startsWith("entity/")) {
                continue;
            }
            if (leaf.equals(name) || leaf.startsWith(name + "_")) {
                found.add(Identifier.fromNamespaceAndPath(entityId.getNamespace(), bare));
            }
        }

        if (!found.isEmpty()) {
            PolymerPatcher.LOGGER.debug("The dump recorded no texture for {}; going by name found {}", entityId, found);
        }
        return found;
    }

    private static Set<Identifier> siblings(Identifier entityId, Identifier texture) {
        String path = texture.getPath();
        int slash = path.lastIndexOf('/');
        if (slash < 0) {
            return Set.of();
        }

        // Only textures sitting in the very same folder, so a boot among the equipment is not read as
        // a fish
        String folder = path.substring(0, slash + 1);
        String name = entityId.getPath();

        Set<Identifier> found = new LinkedHashSet<>();
        for (String file : index().getOrDefault(texture.getNamespace(), List.of())) {
            if (!file.startsWith(TEXTURES + folder) || !file.endsWith(PNG)) {
                continue;
            }

            String bare = file.substring(TEXTURES.length() + folder.length(), file.length() - PNG.length());
            if (bare.indexOf('/') >= 0) {
                continue;
            }

            if (bare.equals(name) || bare.startsWith(name + "_")) {
                found.add(Identifier.fromNamespaceAndPath(texture.getNamespace(), folder + bare));
            }
        }

        return found;
    }

    private static Map<String, List<String>> index() {
        Map<String, List<String>> known = index;
        if (known != null) {
            return known;
        }

        Map<String, List<String>> built = new HashMap<>();
        try {
            ResourceHelper.GLOBAL_ASSETS.locateFiles("").forEach(tuple -> {
                Identifier id = tuple.getFirst();
                if (id.getPath().startsWith(TEXTURES) && id.getPath().endsWith(PNG)) {
                    built.computeIfAbsent(id.getNamespace(), namespace -> new ArrayList<>()).add(id.getPath());
                }
            });
        } catch (Throwable e) {
            // Without the index nothing is expanded, which is exactly where this started
            PolymerPatcher.LOGGER.warn("Could not read the installed textures; mobs will only be drawn in the skin the dump recorded", e);
        }

        index = built;
        return built;
    }
}
