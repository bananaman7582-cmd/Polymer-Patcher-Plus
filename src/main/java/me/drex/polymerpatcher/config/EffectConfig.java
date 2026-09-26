package me.drex.polymerpatcher.config;

/** What a player is told about things a client without the mods cannot show them. */
public class EffectConfig {

    /**
     * Name a modded effect above the hotbar when it starts, and again when it wears off.
     * <p>
     * A modded effect has to reach a stranger as some vanilla one, because the icon in the corner is
     * drawn from a list the client already has - so what a player actually sees is an unrelated icon
     * appearing, with no way of telling what happened to them or when it ends. The name makes up for
     * that without needing anything installed.
     */
    public boolean announceModdedEffects = true;
}
