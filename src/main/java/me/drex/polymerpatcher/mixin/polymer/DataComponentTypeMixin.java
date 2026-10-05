package me.drex.polymerpatcher.mixin.polymer;

import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import me.drex.polymerpatcher.util.NativeItemSync;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Lets a custom component type use its real registry entry only for a client that proved it has the
 * owning mod at the server's exact version.
 *
 * <p>Doing this as part of Fabric's original registry sync matters. A later partial registry remap makes
 * 26.2 rebake every item's default components against a deliberately sparse item registry, producing a
 * null holder during login. The original pass has the complete registry context and is safe.</p>
 */
@Mixin(DataComponentType.class)
public interface DataComponentTypeMixin extends PolymerSyncedObject<DataComponentType<?>> {
    @Override
    default DataComponentType<?> getPolymerReplacement(DataComponentType<?> original, PacketContext context) {
        return original;
    }

    @Override
    default boolean canSynchronizeToPolymerClient(PacketContext context) {
        return polymerPatcher$clientHasType(context);
    }

    @Override
    default boolean canSyncRawToClient(PacketContext context) {
        return polymerPatcher$clientHasType(context);
    }

    private boolean polymerPatcher$clientHasType(PacketContext context) {
        Identifier id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey((DataComponentType<?>) (Object) this);
        return id != null && NativeItemSync.hasMatchingNamespace(context, id.getNamespace());
    }
}
