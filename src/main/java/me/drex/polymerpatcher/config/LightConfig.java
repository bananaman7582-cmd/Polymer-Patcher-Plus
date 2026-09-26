package me.drex.polymerpatcher.config;

/** What a client without the mods is told about light the mods draw themselves. */
public class LightConfig {

    /**
     * Send a mod's own biome ambient light as a biome attribute the game already understands.
     * <p>
     * Alex's Caves brightens its caves from the client, which leaves them pitch black for anyone without
     * the mod. The same thing can be said in the biome data every client is sent, so it is.
     */
    public boolean modBiomeAmbientLight = true;

    /**
     * How much of it to send, where one is exactly what the mod draws for itself. Raise it for caves that
     * should read as lit rather than as merely not black.
     */
    public double modBiomeAmbientLightScale = 1.0;

    /**
     * The smallest coloured ambient contribution worth encoding in an eight-bit vanilla biome.
     * Very small shader values (the toxic caves use 0.01) quantize to almost black and become visible
     * only under fullbright. This floor keeps their intended colour legible without brightening the
     * already brighter cave biomes.
     */
    public double modBiomeAmbientLightMinimum = 0.04;
}
