package me.drex.polymerpatcher.duck;

import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * Lets a render state say which entity it was taken from.
 * <p>
 * A render state is deliberately a copy: the client takes what it needs off an entity once, and draws from
 * the copy, so that drawing never touches the entity itself. Some mods want the entity anyway - to pick a
 * texture from it, usually - and reach it by adding a method to the state through a mixin of their own.
 * That mixin is part of their client and never runs here, so their renderer asks a question the state
 * cannot answer and throws while being drawn.
 * <p>
 * The same answer is provided here instead, and the entity is put in as each state is filled in.
 */
public interface ICapturedEntity {

    void polymerPatcher$capture(@Nullable Entity entity, float partialTick);

    @Nullable
    Entity polymerPatcher$entity();

    float polymerPatcher$partialTick();
}
