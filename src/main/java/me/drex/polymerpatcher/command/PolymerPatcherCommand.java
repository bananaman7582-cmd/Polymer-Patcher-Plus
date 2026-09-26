package me.drex.polymerpatcher.command;

import com.mojang.brigadier.CommandDispatcher;
import eu.pb4.polymer.virtualentity.api.attachment.BlockAwareAttachment;
import eu.pb4.polymer.virtualentity.impl.HolderHolder;
import me.drex.polymerpatcher.mixin.debug.EntityAttachmentAccessor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class PolymerPatcherCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("pp")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("count-displays")
                    .then(Commands.literal("block").executes(ctx -> {
                        var player = ctx.getSource().getPlayerOrException();
                        var map = new HashMap<Block, DisplayCountResult>();
                        for (var holder : ((HolderHolder) player.connection).polymer$getHolders()) {
                            if (holder.getAttachment() instanceof BlockAwareAttachment attachment) {
                                map.compute(attachment.getBlockState().getBlock(), (entityType, displayCountResult) -> {
                                    if (displayCountResult == null) displayCountResult = DisplayCountResult.DEFAULT;
                                    return new DisplayCountResult(displayCountResult.attachments + 1, displayCountResult.elements + holder.getElements().size());
                                });
                            }
                        }
                        DisplayCountResult global = map.values().stream().reduce(DisplayCountResult.DEFAULT, (displayCountResult, displayCountResult2) -> new DisplayCountResult(displayCountResult.attachments + displayCountResult2.attachments, displayCountResult.elements + displayCountResult2.elements));
                        var text = Component.literal("Global: " + global.attachments + " attachments, " + global.elements + " elements\n\n");
                        map.entrySet().stream().sorted(Map.Entry.<Block, DisplayCountResult>comparingByValue().reversed()).forEach(entry -> text.append(Component.literal(BuiltInRegistries.BLOCK.getKey(entry.getKey()) + " -> " + entry.getValue().elements + " elements on " + entry.getValue().attachments + " attachments\n\n")));
                        player.openDialog(Holder.direct(new NoticeDialog(new CommonDialogData(Component.literal("Entity attachment counts from blocks"), Optional.empty(), true, true, DialogAction.CLOSE, List.of(new PlainMessage(text, 300)), List.of()), new ActionButton(new CommonButtonData(CommonComponents.GUI_OK, CommonButtonData.DEFAULT_WIDTH), Optional.empty()))));
                        return 1;
                    })).then(Commands.literal("entity").executes(ctx -> {
                        var player = ctx.getSource().getPlayerOrException();
                        var map = new HashMap<EntityType<?>, DisplayCountResult>();
                        for (var holder : ((HolderHolder) player.connection).polymer$getHolders()) {
                            if (holder.getAttachment() instanceof EntityAttachmentAccessor accessor) {
                                map.compute(accessor.getEntity().getType(), (entityType, displayCountResult) -> {
                                    if (displayCountResult == null) displayCountResult = DisplayCountResult.DEFAULT;
                                    return new DisplayCountResult(displayCountResult.attachments + 1, displayCountResult.elements + holder.getElements().size());
                                });
                            }
                        }
                        DisplayCountResult global = map.values().stream().reduce(DisplayCountResult.DEFAULT, (displayCountResult, displayCountResult2) -> new DisplayCountResult(displayCountResult.attachments + displayCountResult2.attachments, displayCountResult.elements + displayCountResult2.elements));
                        var text = Component.literal("Global: " + global.attachments + " attachments, " + global.elements + " elements\n\n");

                        map.entrySet().stream().sorted(Map.Entry.<EntityType<?>, DisplayCountResult>comparingByValue().reversed()).forEach(entry -> text.append(Component.literal(BuiltInRegistries.ENTITY_TYPE.getKey(entry.getKey()) + " -> " + entry.getValue().elements + " elements on " + entry.getValue().attachments + " attachments\n\n")));

                        player.openDialog(Holder.direct(new NoticeDialog(new CommonDialogData(Component.literal("Entity attachment counts from entities"), Optional.empty(), true, true, DialogAction.CLOSE, List.of(new PlainMessage(text, 300)), List.of()), new ActionButton(new CommonButtonData(CommonComponents.GUI_OK, CommonButtonData.DEFAULT_WIDTH), Optional.empty()))));
                        return 1;
                    })))
                // Answers "why can nobody see this" without a client, and from the console, which is the
                // only way to ask it about an entity that is invisible precisely because nothing is drawn
                .then(Commands.literal("block")
                    .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                        .executes(ctx -> {
                            var player = ctx.getSource().getPlayerOrException();
                            var pos = net.minecraft.commands.arguments.coordinates.BlockPosArgument.getLoadedBlockPos(ctx, "pos");
                            String report = describeBlock(player, pos);
                            ctx.getSource().sendSuccess(() -> Component.literal(report), false);
                            return 1;
                        })))
                .then(Commands.literal("inspect")
                    .then(Commands.argument("targets", net.minecraft.commands.arguments.EntityArgument.entities())
                        .executes(ctx -> {
                            var source = ctx.getSource();
                            int seen = 0;
                            for (var entity : net.minecraft.commands.arguments.EntityArgument.getEntities(ctx, "targets")) {
                                source.sendSuccess(() -> Component.literal(describe(entity)), false);
                                seen++;
                            }
                            if (seen == 0) {
                                source.sendSuccess(() -> Component.literal("Nothing matched."), false);
                            }
                            return seen;
                        })))
        );
    }

    /**
     * What one block is on the server, what it is carried on, and what number the asking player's client is
     * actually sent for it - with what that client reads the number as.
     * <p>
     * Also what it would be sent as if it were converted a second time. The number a client is sent for a
     * vanilla block is worked out by swapping in whichever server block currently sits at the number the
     * client expects; once a mod has shifted vanilla numbering that is a different block, and anything that
     * converts a block twice would convert that different block. If the two lines disagree, and the player
     * sees the second, that is the fault.
     */
    private static String describeBlock(net.minecraft.server.level.ServerPlayer player, net.minecraft.core.BlockPos pos) {
        var real = player.level().getBlockState(pos);
        var carrier = eu.pb4.polymer.core.api.block.PolymerBlockUtils.getPolymerBlockState(real, null);
        var sent = new net.minecraft.world.level.block.state.BlockState[2];
        net.fabricmc.fabric.api.networking.v1.context.PacketContext.runWithContext(
            (net.fabricmc.fabric.api.networking.v1.context.PacketContextProvider) (Object) player.connection, () -> {
                var context = net.fabricmc.fabric.api.networking.v1.context.PacketContext.get();
                sent[0] = eu.pb4.polymer.core.api.block.PolymerBlockUtils.getPolymerBlockState(real, context);
                sent[1] = eu.pb4.polymer.core.api.block.PolymerBlockUtils.getPolymerBlockState(sent[0], context);
            });

        StringBuilder out = new StringBuilder();
        out.append("Block at ").append(pos.toShortString()).append(": ").append(real)
            .append("\n  carrier: ").append(carrier);
        for (int pass = 0; pass < 2; pass++) {
            int id = net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.getId(sent[pass]);
            String reads = me.drex.polymerpatcher.util.BlockSyncCheck.clientStateNameAt(id);
            out.append(pass == 0 ? "\n  sent to you as #" : "\n  if converted twice, #").append(id)
                .append(", which your client reads as ").append(reads == null ? "(nothing - not a vanilla number)" : reads);
        }
        return out.toString();
    }

    /**
     * What this mod is doing about one entity: whether it built a drawing for its type, what is attached to
     * it now, and how many pieces that drawing currently has. An entity nobody can see is one of
     * "no drawing", "a drawing that made nothing", or "drawn, so look elsewhere".
     */
    private static String describe(net.minecraft.world.entity.Entity entity) {
        EntityType<?> type = entity.getType();
        var id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        boolean drawing = me.drex.polymerpatcher.entity.AnimatedEntities.hasDrawing(type);

        var model = eu.pb4.polymer.virtualentity.api.attachment.UniqueIdentifiableAttachment.get(
            entity, me.drex.polymerpatcher.entity.AutomaticPolymerEntity.MODEL);
        var placeholder = eu.pb4.polymer.virtualentity.api.attachment.UniqueIdentifiableAttachment.get(
            entity, me.drex.polymerpatcher.entity.AutomaticPolymerEntity.PLACEHOLDER);

        StringBuilder out = new StringBuilder(String.valueOf(id));
        out.append(drawing ? ": has a drawing" : ": NO drawing was built for this type");

        if (model != null) {
            var holder = model.holder();
            out.append(", attached (").append(holder.getClass().getSimpleName()).append(") with ")
                .append(holder.getElements().size()).append(" piece(s)");

            // A piece that exists still draws nothing if it is holding air, wearing a model the pack does
            // not have, or scaled to nothing - which is the difference between "it is drawn" and "I can
            // see it", and the only way to tell them apart from here
            int shown = 0;
            for (var element : holder.getElements()) {
                if (!(element instanceof eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement item)) {
                    continue;
                }
                if (shown++ >= 4) {
                    out.append("\n    ...");
                    break;
                }

                var stack = item.getItem();
                var itemModel = stack.get(net.minecraft.core.component.DataComponents.ITEM_MODEL);
                var scale = item.getScale();
                out.append("\n    ").append(stack.isEmpty() ? "EMPTY" : BuiltInRegistries.ITEM.getKey(stack.getItem()))
                    .append(" model=").append(itemModel == null ? "none" : itemModel)
                    .append(String.format(" scale=[%.3f %.3f %.3f]", scale.x(), scale.y(), scale.z()))
                    .append(" range=").append(item.getViewRange());
            }
        } else if (placeholder != null) {
            out.append(", showing only a ").append(placeholder.holder().getClass().getSimpleName());
        } else {
            out.append(", nothing attached - this client sees the bare stand-in");
        }

        return out.toString();
    }

    public record DisplayCountResult(int attachments, int elements) implements Comparable<DisplayCountResult> {
        public static DisplayCountResult DEFAULT = new DisplayCountResult(0, 0);

        @Override
        public int compareTo(@NotNull DisplayCountResult displayCountResult) {
            if (this.elements == displayCountResult.elements) {
                return Integer.compare(this.attachments, displayCountResult.attachments);
            } else {
                return Integer.compare(this.elements, displayCountResult.elements);
            }
        }
    }
}
