package me.drex.polymerpatcher.mixin.witherstorm;

import me.drex.polymerpatcher.compat.witherstorm.WitherStormEntityData;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/** The Storm as sent to be drawn from far off: its data and attributes, made readable for each player. */
@Pseudo
@Mixin(targets = "nonamecrackers2.witherstormmod.common.packet.WitherStormToDistantRendererMessage")
public abstract class DistantRendererMessageMixin {
    @Shadow
    private int id;

    @Shadow
    private List<SynchedEntityData.DataValue<?>> packedItems;

    @Shadow
    @org.spongepowered.asm.mixin.Final
    private List<ClientboundUpdateAttributesPacket.AttributeSnapshot> attributes;

    @Redirect(method = "encode", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
        target = "Lnonamecrackers2/witherstormmod/common/packet/WitherStormToDistantRendererMessage;packedItems:Ljava/util/List;"))
    private List<SynchedEntityData.DataValue<?>> polymerPatcher$readableData(@Coerce Object self) {
        return WitherStormEntityData.data(packedItems, id);
    }

    @Redirect(method = "encode", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
        target = "Lnonamecrackers2/witherstormmod/common/packet/WitherStormToDistantRendererMessage;attributes:Ljava/util/List;"))
    private List<ClientboundUpdateAttributesPacket.AttributeSnapshot> polymerPatcher$readableAttributes(@Coerce Object self) {
        return WitherStormEntityData.attributes(attributes);
    }
}
