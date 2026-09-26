package me.drex.polymerpatcher.entity;

import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The skins the game itself hands to a player who has none of their own.
 * <p>
 * A renderer on a server has no connection to answer for the uuid it is drawing, so it asks the game
 * for a stand-in, and the game picks by hashing the uuid against these eighteen faces. The dump only
 * ever records whichever one that hash happened to land on, so every other stand-in named a folder the
 * pack never wrote - a path with no model file, drawn by the client as a cube. A UUID is a coin toss
 * between eighteen skins, so an entity whose skin is chosen this way came out right for one eighteenth
 * of its uuids and a cube for the rest.
 */
public final class DefaultSkins {

    /** The nine faces the game can choose, in the order it knows them. */
    private static final List<String> FACES =
        List.of("alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri");

    /** Every stand-in skin the game can pick, in the order it keeps them: the slim faces, then the wide ones. */
    public static final List<Identifier> ALL = build();

    private static final String SLIM = "entity/player/slim/";
    private static final String WIDE = "entity/player/wide/";

    private DefaultSkins() {
    }

    private static List<Identifier> build() {
        List<Identifier> skins = new ArrayList<>(FACES.size() * 2);
        for (String face : FACES) {
            skins.add(Identifier.fromNamespaceAndPath(Identifier.DEFAULT_NAMESPACE, SLIM + face));
        }
        for (String face : FACES) {
            skins.add(Identifier.fromNamespaceAndPath(Identifier.DEFAULT_NAMESPACE, WIDE + face));
        }
        return List.copyOf(skins);
    }

    private static boolean isDefault(Identifier texture) {
        String path = texture.getPath();
        return texture.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)
            && (path.startsWith(SLIM) || path.startsWith(WIDE));
    }

    /**
     * The textures handed in plus every stand-in skin the game can pick, when any of them is one.
     * <p>
     * A mod's own skin - a custom face it registers and binds itself - is left alone; only the
     * game's stand-ins are names every uuid can land on.
     */
    public static Set<Identifier> expand(Set<Identifier> textures) {
        boolean any = false;
        for (Identifier texture : textures) {
            if (isDefault(texture)) {
                any = true;
                break;
            }
        }
        if (!any) {
            return textures;
        }

        // The recorded one stays first, so the ordering decisions downstream keep whatever they chose
        Set<Identifier> all = new LinkedHashSet<>(textures);
        all.addAll(ALL);
        return all;
    }
}