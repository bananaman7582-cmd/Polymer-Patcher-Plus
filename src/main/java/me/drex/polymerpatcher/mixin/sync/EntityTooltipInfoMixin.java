package me.drex.polymerpatcher.mixin.sync;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.registry.RegistryPatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps a mob a player has never heard of out of the text they are shown.
 * <p>
 * Hovering text can carry an entity, and what travels is the entity's <b>name</b> - the plain string
 * {@code minecraft:cushion} - which the client looks up in its own list of entity types. There is no
 * stand-in step here and no number to rewrite: a name it does not know is an error thrown while reading
 * the message, and a message that cannot be read ends the connection.
 * <p>
 * FallDrop Backport registers its cushion as {@code minecraft:cushion}, and shows the health of whatever
 * you are sitting on in the action bar with the cushion attached to it. Sitting on one was a
 * disconnection, and because you were still sitting on it when you came back, so was every attempt to
 * rejoin - the message was sent again before the world had finished loading.
 * <p>
 * So an entity the game does not have is replaced with one it does. The name written beside it is left
 * exactly as it was, which is the part a player actually reads - the tooltip still says "cushion", and
 * only the line naming its type changes.
 * <p>
 * Done as the tooltip is built rather than as it is sent, because that is the last point where there is
 * still something to change: by the time the message is being written out it is a string, and by then
 * the only options are to send it or to drop the message.
 */
@Mixin(HoverEvent.EntityTooltipInfo.class)
public class EntityTooltipInfoMixin {

    /** Types already replaced, so a message sent every tick is mentioned once. */
    private static final Set<EntityType<?>> REPORTED = ConcurrentHashMap.newKeySet();

    @Inject(method = "<init>(Lnet/minecraft/world/entity/EntityType;Ljava/util/UUID;Ljava/util/Optional;)V",
        at = @At("RETURN"))
    private void polymer_patcher$replaceUnknownType(EntityType<?> type, UUID uuid, Optional<net.minecraft.network.chat.Component> name, CallbackInfo ci) {
        try {
            if (type == null) {
                return;
            }

            Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (id == null || RegistryPatcher.isVanillaEntityType(id)) {
                return;
            }

            if (REPORTED.add(type)) {
                PolymerPatcher.LOGGER.info(
                    "{} is named in hovering text but no vanilla client has such an entity; it will be shown as an item display so the message can be read",
                    id);
            }

            // The same thing this mod sends in place of the entity itself, so the two agree
            ((EntityTooltipInfoAccessor) this).polymer_patcher$setType(EntityTypes.ITEM_DISPLAY);
        } catch (Throwable e) {
            // A tooltip that will not be checked is better left as it was than not built at all
        }
    }
}
