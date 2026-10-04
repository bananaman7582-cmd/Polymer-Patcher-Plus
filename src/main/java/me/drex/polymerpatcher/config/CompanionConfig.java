package me.drex.polymerpatcher.config;

/** The optional client-side companion mod, Polymer Patcher++ Client. */
public class CompanionConfig {

    /**
     * Offer players who install the companion this server's real blocks and fluids.
     * <p>
     * A player with the companion is sent a description of every modded block the first time they join.
     * After they restart their game, their client holds a real copy of each block - its own shape, light,
     * sounds, hardness and model - in place of the vanilla carrier everybody else sees, and modded fluids
     * become real fluids. Turned off, companion players are treated exactly like everybody else.
     */
    public boolean enabled = true;
}
