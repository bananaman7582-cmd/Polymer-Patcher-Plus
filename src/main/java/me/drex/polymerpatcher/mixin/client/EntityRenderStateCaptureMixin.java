package me.drex.polymerpatcher.mixin.client;

import me.drex.polymerpatcher.duck.ICapturedEntity;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Keeps the entity a render state was taken from, for the mods that ask for it back.
 * <p>
 * Alex's Caves' arrow renderer picks its texture straight off the entity - {@code ACStateAccess.entity(state)}
 * - and gets there by adding that method to every render state through a client mixin of its own. A server
 * runs no client mixins, so the state it is handed has no such method, the cast fails, and the renderer
 * throws while drawing: {@code ArrowRenderState cannot be cast to ACStateAccess}. Every seeking arrow on
 * this server was invisible for exactly that reason, for everybody.
 * <p>
 * So the same three methods are put on the state here, under the names that mod looks for, and the
 * interface itself is added to the class as the mixin is applied - see {@code PPMixinPlugin}, which can do
 * that without this mod having to be built against a mod it does not depend on. The answer is filled in
 * wherever this mod fills in a state of its own.
 */
@Mixin(EntityRenderState.class)
public class EntityRenderStateCaptureMixin implements ICapturedEntity {

    @Unique
    private @Nullable Entity polymerPatcher$capturedEntity;

    @Unique
    private float polymerPatcher$capturedPartialTick;

    @Override
    public void polymerPatcher$capture(@Nullable Entity entity, float partialTick) {
        this.polymerPatcher$capturedEntity = entity;
        this.polymerPatcher$capturedPartialTick = partialTick;
    }

    @Override
    public @Nullable Entity polymerPatcher$entity() {
        return this.polymerPatcher$capturedEntity;
    }

    @Override
    public float polymerPatcher$partialTick() {
        return this.polymerPatcher$capturedPartialTick;
    }

    /**
     * Alex's Caves' own names for the same three things. They are spelled out rather than inherited
     * because this mod is not built against that one: the interface they belong to is added to this class
     * at the moment the mixin is applied, and only when that mod is installed.
     */
    public void alexscaves$capture(@Nullable Entity entity, float partialTick) {
        this.polymerPatcher$capture(entity, partialTick);
    }

    public @Nullable Entity alexscaves$entity() {
        return this.polymerPatcher$capturedEntity;
    }

    public float alexscaves$partialTick() {
        return this.polymerPatcher$capturedPartialTick;
    }
}
