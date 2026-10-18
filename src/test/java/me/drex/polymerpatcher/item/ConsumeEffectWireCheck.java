package me.drex.polymerpatcher.item;

import com.mojang.serialization.Lifecycle;
import com.mojang.serialization.MapCodec;
import eu.pb4.polymer.core.impl.networking.TransformingPacketCodec;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.registries.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.*;
import net.minecraft.resources.*;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.consume_effects.*;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.stream.Stream;

/** Reproduces the milk bottle's exact discriminator/payload mismatch, including the bogus effect ID 102. */
public final class ConsumeEffectWireCheck {
    private static ConsumeEffect.Type<TagEffect> CUSTOM;

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        CUSTOM = new ConsumeEffect.Type<>(MapCodec.unit(new TagEffect("farmersdelight:milk_bottle_ignored")),
            StreamCodec.of((buf, effect) -> buf.writeUtf(effect.tag()), buf -> new TagEffect(buf.readUtf())));
        var serverTypes = new MappedRegistry<ConsumeEffect.Type<?>>(Registries.CONSUME_EFFECT_TYPE, Lifecycle.stable());
        for (var type : BuiltInRegistries.CONSUME_EFFECT_TYPE) {
            serverTypes.register(ResourceKey.create(Registries.CONSUME_EFFECT_TYPE,
                BuiltInRegistries.CONSUME_EFFECT_TYPE.getKey(type)), type, RegistrationInfo.BUILT_IN);
        }
        serverTypes.register(ResourceKey.create(Registries.CONSUME_EFFECT_TYPE, Identifier.parse("example:tag_effect")),
            CUSTOM, RegistrationInfo.BUILT_IN);
        serverTypes.freeze();
        RegistryAccess client = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        RegistryAccess server = new RegistryAccess.ImmutableRegistryAccess(Stream.concat(
            client.registries().filter(r -> !r.key().equals(Registries.CONSUME_EFFECT_TYPE)),
            Stream.of(new RegistryAccess.RegistryEntry<>(Registries.CONSUME_EFFECT_TYPE, serverTypes))));
        var effect = new TagEffect("farmersdelight:milk_bottle_ignored");
        var typeCodec = TransformingPacketCodec.encodeOnly(
            ByteBufCodecs.<ConsumeEffect.Type<?>>registry(Registries.CONSUME_EFFECT_TYPE),
            (buf, type) -> type == CUSTOM ? ConsumeEffect.Type.APPLY_EFFECTS : type);
        StreamCodec<RegistryFriendlyByteBuf, ConsumeEffect> broken = dispatch(typeCodec);
        var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), server);
        try {
            broken.encode(wire, effect);
            try {
                ConsumeEffect.STREAM_CODEC.decode(new RegistryFriendlyByteBuf(wire, client));
                throw new AssertionError("Type-only replacement unexpectedly decoded");
            } catch (IllegalArgumentException expected) {
                if (!"No value with id 102".equals(expected.getMessage())) throw expected;
                System.out.println("Reproduced milk-bottle consume-effect failure: " + expected.getMessage());
            }
            wire.clear();
            ConsumeEffect safe = ClientSafeConsumeEffects.forReplacement(effect, ConsumeEffect.Type.APPLY_EFFECTS);
            broken.encode(wire, safe);
            var decoded = ConsumeEffect.STREAM_CODEC.decode(new RegistryFriendlyByteBuf(wire, client));
            if (!(decoded instanceof ApplyStatusEffectsConsumeEffect apply) || !apply.effects().isEmpty()
                || apply.probability() != 0 || wire.isReadable()) throw new AssertionError("Invalid no-op wire value");
            if (!effect.tag().equals("farmersdelight:milk_bottle_ignored")) throw new AssertionError("Mutated server effect");

            if (ClientSafeConsumeEffects.forReplacement(effect, CUSTOM) != effect) {
                throw new AssertionError("Shared/native effect was replaced");
            }
            for (ConsumeEffect vanilla : List.of(new ApplyStatusEffectsConsumeEffect(List.of(), 1F),
                    new ClearAllStatusEffectsConsumeEffect(), new TeleportRandomlyConsumeEffect(16F))) {
                if (ClientSafeConsumeEffects.forReplacement(vanilla, vanilla.getType()) != vanilla) {
                    throw new AssertionError("Vanilla effect changed");
                }
            }
            wire.clear();
            dispatch(ByteBufCodecs.<ConsumeEffect.Type<?>>registry(Registries.CONSUME_EFFECT_TYPE)).encode(wire, effect);
            if (!effect.equals(ConsumeEffect.STREAM_CODEC.decode(wire)) || wire.isReadable()) {
                throw new AssertionError("Native custom codec did not round-trip");
            }
        } finally {
            wire.release();
        }
        System.out.println("Verified whole-value consume-effect fallback, native preservation and vanilla effects");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static StreamCodec<RegistryFriendlyByteBuf, ConsumeEffect> dispatch(
        StreamCodec<RegistryFriendlyByteBuf, ConsumeEffect.Type<?>> types) {
        return types.dispatch(ConsumeEffect::getType, type -> (StreamCodec) type.streamCodec());
    }

    private record TagEffect(String tag) implements ConsumeEffect {
        public ConsumeEffect.Type<TagEffect> getType() { return CUSTOM; }
        public boolean apply(Level level, ItemStack stack, LivingEntity entity) { return false; }
    }
}
