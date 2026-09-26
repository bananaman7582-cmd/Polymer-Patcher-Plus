package me.drex.polymerpatcher.mixin.polymer;

import eu.pb4.polymer.core.impl.networking.entry.PolymerBlockEntry;
import me.drex.polymerpatcher.util.BlockSyncCheck;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.objectweb.asm.Opcodes;

/**
 * Keeps the list of blocks a client is handed on joining down to blocks that client can look up.
 * <p>
 * Polymer tells every joining client about each of its blocks, and part of each entry is the state the
 * client should draw - written as that state's <b>number</b>, with no translation: the codec is a plain
 * id mapper. A number means whatever sits at it in the reader's own list, and a client without the mods
 * has nothing past the vanilla ones. So an entry naming a modded state is not a block drawn wrongly - it
 * is a packet that cannot be read, and the connection ends there. The client's report says exactly that:
 * {@code Failed to decode packet (polymer:sync/blocks) … No value with id 108341}.
 * <p>
 * It stayed hidden while this server's modded blocks all had ordinary carriers to point at. Adding a mod
 * with a thousand more blocks than the carriers could cover left some pointing at themselves, and from
 * that moment nobody without the mods could get in at all.
 * <p>
 * The codec does not translate the state through Polymer. It writes the server's raw state number. Even
 * a vanilla carrier is unsafe here when another mod has added states to a vanilla block: every later raw
 * number can then refer to a different state on the client. The metadata therefore uses one early,
 * immutable vanilla state. This does not change the carrier in chunk/block-update packets; it is only the
 * default state stored in Polymer's optional client-side description of the server block. The returned
 * object is deliberately the server-registry token occupying barrier's pristine-client raw id; the
 * direct codec only observes that number.
 */
@Mixin(value = PolymerBlockEntry.class, remap = false)
public class PolymerBlockEntryMixin {

    @Inject(method = "of", at = @At("RETURN"), cancellable = true)
    private static void polymerPatcher$onlyOfferReadableStates(Block block, CallbackInfoReturnable<PolymerBlockEntry> cir) {
        PolymerBlockEntry entry = cir.getReturnValue();
        if (entry == null) {
            return;
        }

        BlockState visual = entry.visual();
        if (visual == null) {
            return;
        }

        if (BlockSyncCheck.isClientReadableMetadata(visual)) {
            return;
        }

        Identifier id = BuiltInRegistries.BLOCK.getKey(block);
        if (id != null) {
            me.drex.polymerpatcher.util.BlockSyncCheck.note(id);
        }

        cir.setReturnValue(new PolymerBlockEntry(
            entry.identifier(),
            entry.numId(),
            entry.hardness(),
            entry.miningDeltaLogic(),
            entry.text(),
            BlockSyncCheck.clientReadableMetadata(),
            entry.visualStack()
        ));
    }

    /**
     * Last line of defence: replace the state at the exact serialization boundary. The earlier injection
     * makes the record itself honest, while this redirect also covers another mod constructing or
     * replacing an entry after {@link PolymerBlockEntry#of(Block)} returned.
     */
    @Redirect(
        method = "write",
        at = @At(
            value = "FIELD",
            target = "Leu/pb4/polymer/core/impl/networking/entry/PolymerBlockEntry;visual:Lnet/minecraft/world/level/block/state/BlockState;",
            opcode = Opcodes.GETFIELD
        )
    )
    private BlockState polymerPatcher$serializeReadableState(PolymerBlockEntry entry) {
        BlockState visual = entry.visual();
        if (!BlockSyncCheck.isClientReadableMetadata(visual)) {
            BlockSyncCheck.note(entry.identifier());
        }
        return BlockSyncCheck.clientReadableMetadata();
    }

}
