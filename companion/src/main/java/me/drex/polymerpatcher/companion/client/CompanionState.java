package me.drex.polymerpatcher.companion.client;

import org.jspecify.annotations.Nullable;

/**
 * Whether this session's server confirmed that the blocks registered at startup are its own.
 * <p>
 * Until it does, Polymer is told not to swap anything: a block registered from an older manifest may
 * no longer match, and swapping in a block the server no longer means is worse than leaving the
 * carrier the server sent.
 */
public final class CompanionState {

    private CompanionState() {
    }

    private static volatile boolean decoding;
    private static volatile @Nullable String notice;

    public static boolean decoding() {
        return decoding;
    }

    static void setDecoding(boolean value) {
        decoding = value;
    }

    /** Something to tell the player once they are in the world, or null. */
    static @Nullable String takeNotice() {
        String taken = notice;
        notice = null;
        return taken;
    }

    static void setNotice(@Nullable String value) {
        notice = value;
    }
}
