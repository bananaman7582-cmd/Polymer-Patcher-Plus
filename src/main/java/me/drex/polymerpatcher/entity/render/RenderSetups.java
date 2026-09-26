package me.drex.polymerpatcher.entity.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * The few things worth asking a render pipeline on a server, where nothing is ever actually drawn.
 */
public final class RenderSetups {

    /** The sampler the game binds an entity's own texture to; the others are the light and overlay maps. */
    private static final String MAIN_SAMPLER = "Sampler0";

    private RenderSetups() {
    }

    /**
     * The texture a mob is actually wearing, named the way the generated models are filed.
     * <p>
     * A render type binds more than one texture - the skin, the light map, the overlay - and keeps them
     * in a map by sampler name. Taking whichever came out of that map first is a coin toss, and losing
     * it means building a model path out of the light map: a path no model was ever written to, an item
     * with no model, and a mob that is simply not there. Which mobs lost the toss depended on nothing
     * more than hash order, which is why some rendered and some did not.
     *
     * @return the texture, or null when this render type binds none
     */
    @Nullable
    public static Identifier mainTexture(Map<String, RenderSetup.TextureBinding> textures) {
        if (textures.isEmpty()) {
            return null;
        }

        RenderSetup.TextureBinding binding = textures.get(MAIN_SAMPLER);

        if (binding == null) {
            // Nothing named the way the game names it, so take the first by name rather than by hash -
            // wrong is survivable, differing between two runs of the same server is not, because the
            // models were written on one of them
            binding = textures.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        }

        return binding == null ? null : strip(binding.location());
    }

    /**
     * A texture identifier as the generated models are filed under it.
     */
    public static Identifier strip(Identifier location) {
        return location.withPath(location.getPath().replace("textures/", "").replace(".png", ""));
    }

    /**
     * Whether what this pipeline draws is blended rather than laid down solid, which decides how the
     * generated model for a part is written.
     * <p>
     * 26.2 moved the blend function off the pipeline and onto its colour targets, of which there can be
     * more than one; a pipeline blends if any of them does. Read through the public accessors rather
     * than an {@code @Accessor} mixin, because a field that moves takes the mixin's whole target class
     * down with it, and this one has moved once already.
     */
    public static boolean isTranslucent(RenderPipeline pipeline) {
        for (ColorTargetState target : pipeline.getColorTargetStates()) {
            if (target == null) continue;

            BlendFunction blend = target.blendFunction().orElse(null);
            if (blend == BlendFunction.TRANSLUCENT || blend == BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA) {
                return true;
            }
        }

        return false;
    }
}
