package me.drex.polymerpatcher.mixin.polymer;

import eu.pb4.polymer.core.impl.networking.entry.PolymerBlockEntry;
import me.drex.polymerpatcher.util.BlockSyncCheck;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
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
 * number can then refer to a different state on the client. The entry therefore keeps its semantic
 * carrier for server-side integrations, while the serializer is handed the server-registry token that
 * occupies that carrier's pristine-client raw id. The direct codec observes the safe number; Polymer and
 * Jade still observe which carrier the block actually uses.
 */
@Mixin(value = PolymerBlockEntry.class, remap = false)
public class PolymerBlockEntryMixin {

    /**
     * Translate the semantic state only at the exact serialization boundary. Keeping the record intact
     * lets Polymer/Jade identify the block before its raw numeric id is made safe for the client.
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
        if (!BlockSyncCheck.isClientReadableWorldState(visual)) {
            BlockSyncCheck.note(entry.identifier());
        }
        return BlockSyncCheck.clientReadableWorldState(visual);
    }

}
