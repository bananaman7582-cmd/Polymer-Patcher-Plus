package me.drex.polymerpatcher.item;

import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import eu.pb4.polymer.networking.api.ContextByteBuf;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.fabricmc.fabric.api.networking.v1.context.PacketContextProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.item.consume_effects.ConsumeEffect;

import java.util.List;

/** Keeps a dispatched consume-effect's wire type and value schema in agreement. */
public final class ClientSafeConsumeEffects {
    private static final ConsumeEffect NO_OP = new ApplyStatusEffectsConsumeEffect(List.of(), 0.0F);

    private ClientSafeConsumeEffects() { }

    public static ConsumeEffect forWire(RegistryFriendlyByteBuf buf, ConsumeEffect effect) {
        PacketContext context = PacketContext.get();
        if (buf instanceof ContextByteBuf polymerBuf && polymerBuf.clientConnection() != null) {
            context = ((PacketContextProvider) (Object) polymerBuf.clientConnection()).getPacketContext();
        }
        if (!(buf instanceof ContextByteBuf)
            && (context == null || context.get(PacketContext.CONNECTION) == null)) {
            return effect; // Local buffer copies must retain the server's real effect.
        }
        var type = effect.getType();
        var synced = PolymerSyncedObject.getSyncedObject(BuiltInRegistries.CONSUME_EFFECT_TYPE, type);
        return forReplacement(effect, synced == null ? type : synced.getPolymerReplacement(type, context));
    }

    static ConsumeEffect forReplacement(ConsumeEffect effect, ConsumeEffect.Type<?> replacement) {
        // A registry replacement changes only the discriminator, not the dispatch codec selected from
        // the original type. Writing an unrelated custom payload under apply_effects makes the client
        // parse arbitrary bytes as effect IDs (milk_bottle's 'f' became 102). Replace the WHOLE wire value.
        // An unchanged type, including a genuinely native/shared type, keeps its actual payload.
        return replacement == effect.getType() ? effect : NO_OP;
    }
}
