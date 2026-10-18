package me.drex.polymerpatcher.mixin.witherstorm;

import me.drex.polymerpatcher.compat.witherstorm.WitherStormEntityData;
import net.minecraft.network.syncher.SynchedEntityData;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/** The Storm's data updates, made readable for each player. */
@Pseudo
@Mixin(targets = "nonamecrackers2.witherstormmod.common.packet.StormMetadataMessage")
public abstract class StormMetadataMessageMixin {
    @Shadow
    private int entityId;

    @Shadow
    private List<SynchedEntityData.DataValue<?>> packedItems;

    @Redirect(method = "encode", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
        target = "Lnonamecrackers2/witherstormmod/common/packet/StormMetadataMessage;packedItems:Ljava/util/List;"))
    private List<SynchedEntityData.DataValue<?>> polymerPatcher$readableData(@Coerce Object self) {
        return WitherStormEntityData.data(packedItems, entityId);
    }
}
