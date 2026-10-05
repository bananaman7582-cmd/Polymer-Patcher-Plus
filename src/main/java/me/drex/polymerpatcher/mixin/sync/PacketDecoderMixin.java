package me.drex.polymerpatcher.mixin.sync;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import me.drex.polymerpatcher.util.ClientNumbering;
import net.minecraft.network.PacketDecoder;
import org.spongepowered.asm.mixin.Mixin;

import java.util.List;

/**
 * Marks the stretch of time a packet a client sent is being read, which is the only time a number in it is
 * the client's rather than this server's. See {@link ClientNumbering#fromClient}.
 */
@Mixin(PacketDecoder.class)
public class PacketDecoderMixin {
    @WrapMethod(method = "decode")
    private void polymerPatcher$readingFromClient(ChannelHandlerContext context, ByteBuf input, List<Object> out,
                                                  Operation<Void> original) {
        ClientNumbering.whileReading(() -> original.call(context, input, out));
    }
}
