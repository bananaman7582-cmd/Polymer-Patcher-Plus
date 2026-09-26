package me.drex.polymerpatcher.entity;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.elements.InteractionElement;
import eu.pb4.polymer.virtualentity.api.elements.VirtualElement;
import me.drex.polymerpatcher.util.NativeClients;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;

/**
 * The box a player's crosshair finds where a modded mob is standing.
 * <p>
 * A vanilla client is not sent the mob. It is sent an item display, and an item display has no size at
 * all - nothing to point at, nothing to hit, nothing to hang a name tag on. So the mob's own box is sent
 * alongside it as an interaction entity, whose width and height are carried in its data rather than
 * fixed by its type, and which reports every click and swing back to the real mob behind it.
 * <p>
 * That is why the box is not "the closest vanilla mob". A vanilla mob's size comes from its type and
 * cannot be changed per entity, so a raccoon shown as an ocelot would be an ocelot-shaped box - close
 * for one mob and wrong for the next, and wrong by a lot for anything very large or very small. The
 * interaction is the exact size, to the same hundredth as the server's own.
 * <p>
 * What it does not do is push. Nothing a client can be given carries an arbitrary width and height
 * <em>and</em> collides, so a player still walks into a modded mob rather than being stopped by it -
 * the server pushes them back out, which is felt as the mob shoving rather than blocking.
 */
public class HitboxModel extends ElementHolder {

    public final InteractionElement interaction;

    protected final Entity hitboxEntity;

    private float width = -1;
    private float height = -1;

    public HitboxModel(Entity entity) {
        this.hitboxEntity = entity;
        this.interaction = new InteractionElement(VirtualElement.InteractionHandler.redirect(entity));
        this.interaction.setSendPositionUpdates(false);
        this.addPassengerElement(this.interaction);
    }

    /**
     * A player who has the mod is shown the real mob, which brings its own box. Sending this one too
     * would leave a second hitbox standing in the same place as the first.
     */
    @Override
    public boolean startWatching(ServerGamePacketListenerImpl player) {
        if (NativeClients.has(player.getPlayer(), namespace())) {
            return false;
        }
        return super.startWatching(player);
    }

    private String namespace() {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(hitboxEntity.getType());
        return id != null ? id.getNamespace() : Identifier.DEFAULT_NAMESPACE;
    }

    @Override
    protected void onTick() {
        syncHitbox();
        super.onTick();
    }

    /**
     * Follows the mob's box as it changes.
     * <p>
     * Both measurements are watched, not just the height. A mob that spreads without growing - a
     * crocodile opening its jaws, anything whose pose is wider than its stance - changes width while its
     * height stays exactly what it was, and watching only the height meant that mob kept whatever width
     * it happened to have when it spawned.
     */
    protected final void syncHitbox() {
        float width = hitboxEntity.getBbWidth();
        float height = hitboxEntity.getBbHeight();

        if (width != this.width || height != this.height) {
            this.width = width;
            this.height = height;
            this.interaction.setSize(width, height);
            onHitboxResized(width, height);
        }
    }

    /** Told when the box changed, for the things that hang off it. */
    protected void onHitboxResized(float width, float height) {
    }
}
