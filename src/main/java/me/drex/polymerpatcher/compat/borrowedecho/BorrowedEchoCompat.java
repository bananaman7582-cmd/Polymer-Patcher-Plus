package me.drex.polymerpatcher.compat.borrowedecho;

import net.minecraft.network.protocol.Packet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Vanilla-client presentation rules for Borrowed Echo.
 *
 * <p>Borrowed Echo's renderer is not merely a player renderer. In a player disguise it applies the
 * mod's continuous glitched-body animation and later the neck-snap/rupture poses; in a passive
 * disguise it constructs the selected creature and submits that creature's renderer. Player forms
 * must remain on that captured renderer so their slim/wide body and the Echo's neck/glitch animation
 * survive. A vanilla passive disguise is the useful exception: its submitted vanilla model parts do
 * not have generated item-display assets, while a native entity carrier already gives a vanilla
 * client the exact same creature model and animation.</p>
 */
public final class BorrowedEchoCompat {
    private static final String CLASS_NAME = "com.borrowedecho.entity.BorrowedEchoEntity";
    private static final Identifier ENTITY_ID = Identifier.fromNamespaceAndPath("borrowed_echo", "borrowed_echo");
    private static final Identifier ANATOMY_TEXTURE = Identifier.fromNamespaceAndPath("borrowed_echo", "entity/true_self");
    private static volatile @Nullable Access access;
    private static volatile boolean unavailable;

    private BorrowedEchoCompat() {
    }

    /**
     * Uses a native carrier only for a stable vanilla passive disguise. Player disguises deliberately
     * stay captured: a PLAYER carrier has the skin, but cannot perform Borrowed Echo's custom model
     * animation. Transforming, broken-head and true-form states also stay captured.
     */
    public static @Nullable EntityType<?> clientCarrier(Entity entity) {
        Snapshot snapshot = snapshot(entity);
        if (snapshot == null || snapshot.trueForm() || snapshot.transforming() || snapshot.brokenHead()) {
            return null;
        }
        return resolveVanillaEntityType(snapshot.entityType());
    }

    public static boolean isBorrowedEcho(Entity entity) {
        return entity != null && CLASS_NAME.equals(entity.getClass().getName());
    }

    public static boolean suppressVirtualName(Entity entity) {
        return isBorrowedEcho(entity);
    }

    public static boolean usesVanillaCarrier(Entity entity) {
        return clientCarrier(entity) != null;
    }

    public static boolean usesPlayerCarrier(Entity entity) {
        return false;
    }

    public static boolean usesAnimatedOverlay(Entity entity) {
        return false;
    }

    public static boolean hidesVirtualModel(Entity entity) {
        return usesVanillaCarrier(entity);
    }

    /** Every layer selected by Borrowed Echo's own renderer belongs to the captured model. */
    public static boolean allowCapturedTexture(Entity entity, @Nullable Identifier texture) {
        return true;
    }

    /**
     * The dump observes a newly constructed Echo before a rupture can expose its anatomy layer. Add
     * that packaged texture so the already-baked jaw/rib/spine parts have models when the event starts.
     */
    public static Set<Identifier> addEventTextures(Identifier entityId, Set<Identifier> textures) {
        if (!ENTITY_ID.equals(entityId)) {
            return textures;
        }
        Set<Identifier> expanded = textures;
        if (!textures.contains(ANATOMY_TEXTURE)) {
            expanded = new LinkedHashSet<>(textures);
            expanded.add(ANATOMY_TEXTURE);
        }
        return BorrowedEchoPlayerSkins.addTextures(expanded);
    }

    /** Kept as the mixin's stable contract so carrier/display transitions cause a wire respawn. */
    public static Presentation presentation(Entity entity) {
        return new Presentation(clientCarrier(entity), false);
    }

    /** No fake PlayerInfo/team packets are needed when the wire entity remains an item display. */
    public static void aroundEntityPacket(Entity entity, Consumer<Packet<?>> consumer, Packet<?> packet) {
    }

    /** Re-tracks when a passive vanilla carrier becomes a captured transformation, or vice versa. */
    public static void refreshTrackingIfChanged(Entity entity, @Nullable Presentation before) {
        Presentation after = presentation(entity);
        if (after.equals(before)) {
            return;
        }
        if (!(entity.level() instanceof ServerLevel level) || entity.isRemoved()
            || level.getEntity(entity.getId()) != entity) {
            return;
        }
        level.getChunkSource().removeEntity(entity);
        level.getChunkSource().addEntity(entity);
    }

    /**
     * Resolves the full id Borrowed Echo stores. {@code withDefaultNamespace} must not be used here:
     * it turns the already namespaced value {@code minecraft:cow} into an invalid path and was the
     * cause of the passive-disguise command error.
     */
    static @Nullable EntityType<?> resolveVanillaEntityType(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        final Identifier id;
        try {
            id = Identifier.parse(value);
        } catch (RuntimeException invalid) {
            return null;
        }
        if (!Identifier.DEFAULT_NAMESPACE.equals(id.getNamespace())) {
            return null;
        }
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
        return type != null && id.equals(BuiltInRegistries.ENTITY_TYPE.getKey(type)) ? type : null;
    }

    /** The player whose profile this Echo copied, without linking the optional mod at compile time. */
    static @Nullable UUID mimickedUuid(Entity entity) {
        Access methods = access(entity);
        if (methods == null) {
            return null;
        }
        try {
            Optional<?> value = (Optional<?>) methods.mimickedUuid().invoke(entity);
            return value.filter(UUID.class::isInstance).map(UUID.class::cast).orElse(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            unavailable = true;
            return null;
        }
    }

    /** True only while the Echo is stably wearing a copied player's ordinary body. */
    /**
     * Whether this form is drawn in the copied player's skin - which every copied-player form is, the
     * snapped neck included.
     * <p>
     * The snap ambush is the stolen body with its head wrenched round, and Borrowed Echo's own renderer
     * draws it in exactly the skin it drew a moment before: the broken head is an animation and nothing
     * else. Left to that renderer on the server, the skin comes out as the default one, because the
     * server has no player list to look the copied player up in - so the broken form was drawn as Steve.
     * <p>
     * Distinct from {@link #usesCopiedPlayerSkin}, which also decides the body model and so still has to
     * keep the broken form on the wide one.
     */
    static boolean wearsCopiedSkin(Entity entity) {
        Snapshot snapshot = snapshot(entity);
        return snapshot != null
            && !snapshot.trueForm()
            && !snapshot.transforming()
            && snapshot.entityType() == null
            && mimickedUuid(entity) != null;
    }

    static boolean usesCopiedPlayerSkin(Entity entity) {
        Snapshot snapshot = snapshot(entity);
        return snapshot != null
            && !snapshot.trueForm()
            && !snapshot.transforming()
            && !snapshot.brokenHead()
            && snapshot.entityType() == null
            && mimickedUuid(entity) != null;
    }

    private static @Nullable Snapshot snapshot(Entity entity) {
        Access methods = access(entity);
        if (methods == null) {
            return null;
        }
        try {
            Optional<?> entityType = (Optional<?>) methods.mimickedEntityType().invoke(entity);
            return new Snapshot(
                entityType.map(Object::toString).orElse(null),
                (boolean) methods.trueForm().invoke(entity),
                (boolean) methods.transforming().invoke(entity),
                (boolean) methods.brokenHead().invoke(entity)
            );
        } catch (ReflectiveOperationException | RuntimeException e) {
            unavailable = true;
            return null;
        }
    }

    private static @Nullable Access access(Entity entity) {
        if (entity == null || unavailable || !CLASS_NAME.equals(entity.getClass().getName())) {
            return null;
        }
        Access current = access;
        if (current != null) {
            return current;
        }
        try {
            Class<?> type = entity.getClass();
            current = new Access(
                type.getMethod("getMimickedEntityType"),
                type.getMethod("getMimickedUuid"),
                type.getMethod("isTrueForm"),
                type.getMethod("isTransforming"),
                type.getMethod("isBrokenHead")
            );
            access = current;
            return current;
        } catch (ReflectiveOperationException e) {
            unavailable = true;
            return null;
        }
    }

    public record Presentation(@Nullable EntityType<?> carrier, boolean animatedOverlay) {
    }

    private record Snapshot(@Nullable String entityType, boolean trueForm, boolean transforming, boolean brokenHead) {
    }

    private record Access(Method mimickedEntityType, Method mimickedUuid, Method trueForm,
                          Method transforming, Method brokenHead) {
    }
}
