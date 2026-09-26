package me.drex.polymerpatcher.mixin.enderscape;

import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.penumbra.enderscape.registry.block.EnderscapeNoteBlockInstruments;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Replaces Enderscape's server-added enum constants with values every vanilla client owns. */
@Mixin(EnderscapeNoteBlockInstruments.class)
public abstract class EnderscapeNoteBlockInstrumentsMixin {
    @Redirect(method = "<clinit>", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/properties/NoteBlockInstrument;valueOf(Ljava/lang/String;)Lnet/minecraft/world/level/block/state/properties/NoteBlockInstrument;"))
    private static NoteBlockInstrument polymerPatcher$vanillaInstrument(String name) {
        return switch (name) {
            case "SYNTH_BASS" -> NoteBlockInstrument.BASS;
            case "SYNTH_BELL" -> NoteBlockInstrument.BELL;
            default -> NoteBlockInstrument.BELL;
        };
    }
}
