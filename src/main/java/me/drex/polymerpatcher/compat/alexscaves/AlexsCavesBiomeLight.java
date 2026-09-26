package me.drex.polymerpatcher.compat.alexscaves;

import me.drex.polymerpatcher.registry.ModBiomeLight;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/** Exact location and meaning of Alex's Caves' server-side biome light values. */
public final class AlexsCavesBiomeLight {
    private static final String REGISTRY =
        "com.github.alexmodguy.alexscaves.server.level.biome.ACBiomeRegistry";
    private static boolean initialized;

    private AlexsCavesBiomeLight() {
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        Method ambient = method("getBiomeAmbientLight");
        Method colour = method("getBiomeLightColorOverride");
        if (ambient == null) {
            return;
        }

        ModBiomeLight.register(biome -> read(ambient, colour, biome));
    }

    private static @Nullable Method method(String name) {
        try {
            return Class.forName(REGISTRY, true, AlexsCavesBiomeLight.class.getClassLoader())
                .getMethod(name, Holder.class);
        } catch (Throwable notInstalled) {
            return null;
        }
    }

    private static @Nullable ModBiomeLight.Source read(Method ambient, @Nullable Method colour,
                                                         Holder<Biome> biome) {
        try {
            float amount = (float) ambient.invoke(null, biome);
            if (amount <= 0.0F) {
                return null;
            }

            double red = 1.0;
            double green = 1.0;
            double blue = 1.0;
            if (colour != null && colour.invoke(null, biome) instanceof Vec3 tint) {
                red = tint.x;
                green = tint.y;
                blue = tint.z;
            }
            return new ModBiomeLight.Source(amount, red, green, blue);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
