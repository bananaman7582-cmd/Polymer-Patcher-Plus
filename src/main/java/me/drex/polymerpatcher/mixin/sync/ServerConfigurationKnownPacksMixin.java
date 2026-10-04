package me.drex.polymerpatcher.mixin.sync;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import me.drex.polymerpatcher.util.NativeItemSync;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.config.SynchronizeRegistriesTask;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.Queue;

/**
 * Listens to a client's answer about which of this server's packs it has - the one place during joining a
 * Fabric client says which mods it has, and at which versions. See {@link NativeItemSync}.
 */
@Mixin(ServerConfigurationPacketListenerImpl.class)
public abstract class ServerConfigurationKnownPacksMixin extends ServerCommonPacketListenerImpl {
    @Shadow
    @Final
    private Queue<ConfigurationTask> configurationTasks;

    @Shadow
    private SynchronizeRegistriesTask synchronizeRegistriesTask;

    @Unique
    private @Nullable Map<Identifier, Object2IntMap<Identifier>> polymerPatcher$itemSync;

    public ServerConfigurationKnownPacksMixin(MinecraftServer server, Connection connection, CommonListenerCookie cookie) {
        super(server, connection, cookie);
    }

    /**
     * After the move to the server thread - the packet arrives on the network thread first, and this method
     * is entered there once before being handed over - and before the answer is acted on, which sends the tags.
     * <p>
     * A client this answer shows to have one of the mods at another version is turned away right here, and
     * the answer is not acted on: nothing more is sent after the screen telling them why.
     */
    @Inject(method = "handleSelectKnownPacks", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
        shift = At.Shift.AFTER), cancellable = true)
    private void polymerPatcher$readKnownPacks(ServerboundSelectKnownPacks packet, CallbackInfo callback) {
        this.polymerPatcher$itemSync = null;
        if (this.synchronizeRegistriesTask == null) {
            // Vanilla refuses the packet on the next line
            return;
        }
        ServerConfigurationPacketListenerImpl self = (ServerConfigurationPacketListenerImpl) (Object) this;
        this.polymerPatcher$itemSync = NativeItemSync.knownPacksReceived(self, this.server,
            ((SynchronizeRegistriesTaskAccessor) this.synchronizeRegistriesTask).polymerPatcher$requestedPacks(), packet.knownPacks());
        if (NativeItemSync.refuseWhileConfiguring(self)) {
            this.polymerPatcher$itemSync = null;
            callback.cancel();
        }
    }

    /** Just before the registry step finishes and the next step starts, so the item sync can be that next step. */
    @Inject(method = "handleSelectKnownPacks", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/network/ServerConfigurationPacketListenerImpl;finishCurrentTask(Lnet/minecraft/server/network/ConfigurationTask$Type;)V"))
    private void polymerPatcher$queueItemSync(ServerboundSelectKnownPacks packet, CallbackInfo callback) {
        Map<Identifier, Object2IntMap<Identifier>> sync = this.polymerPatcher$itemSync;
        this.polymerPatcher$itemSync = null;
        if (sync != null) {
            NativeItemSync.queue((ServerConfigurationPacketListenerImpl) (Object) this, this.configurationTasks, sync);
        }
    }
}
