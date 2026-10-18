package me.drex.polymerpatcher.mixin.witherstorm;

import me.drex.polymerpatcher.compat.witherstorm.WitherStormEntityData;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/** The Storm's attributes, keeping only those the player's client has. */
@Pseudo
@Mixin(targets = "nonamecrackers2.witherstormmod.common.packet.StormAttributesMessage")
public abstract class StormAttributesMessageMixin {
    @Shadow
    private List<ClientboundUpdateAttributesPacket.AttributeSnapshot> attributes;

    @Redirect(method = "encode", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
        target = "Lnonamecrackers2/witherstormmod/common/packet/StormAttributesMessage;attributes:Ljava/util/List;"))
    private List<ClientboundUpdateAttributesPacket.AttributeSnapshot> polymerPatcher$readableAttributes(@Coerce Object self) {
        return WitherStormEntityData.attributes(attributes);
    }
}
