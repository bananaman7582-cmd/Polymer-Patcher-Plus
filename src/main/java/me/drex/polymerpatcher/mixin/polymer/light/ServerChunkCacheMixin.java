package me.drex.polymerpatcher.mixin.polymer.light;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import me.drex.polymerpatcher.util.PolymerLightUpdateHelper;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {
    @Shadow
    @Final
    private ServerLevel level;

    @WrapMethod(method = "tickChunks()V")
    public void addPolymerLightContext(Operation<Void> original) {
        PolymerLightUpdateHelper.runWithLevel(this.level, original::call);
    }
}
