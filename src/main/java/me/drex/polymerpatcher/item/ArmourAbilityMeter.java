package me.drex.polymerpatcher.item;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.config.ConfigManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shows a client without the mod what a piece of its armour is doing, and how to use it.
 * <p>
 * A mod that gives armour an ability gives it two other things as well: a line telling you which key
 * to press, shown when you put it on, and a gauge on the screen showing how near the ability is to
 * ready. Both are drawn by the mod's own client. Without it the cloak of darkness can be worn, charged
 * and fired with nothing whatever to say so - and {@link ArmourAbility} made it usable without making
 * it findable, which is most of the way to nobody ever using it.
 * <p>
 * Neither half is hardcoded to an item. The line is offered to any armour that would have answered the
 * mod's key packet; the gauge to any item that will say how full it is. Alex's Caves says so through
 * {@code getMeterProgress}, which is the method its own HUD reads, so what a vanilla client is shown is
 * the same number at the same moment - drawn as a boss bar, because that is the one gauge a client
 * without the mod already knows how to draw.
 */
public final class ArmourAbilityMeter {

    private ArmourAbilityMeter() {
    }

    /** How often the worn armour is looked at. Five times a second is finer than the eye wants. */
    private static final int EVERY = 4;

    /** The gauge each player is being shown, while they are being shown one. */
    private static final Map<UUID, ServerBossEvent> BARS = new ConcurrentHashMap<>();

    /** What each player was last told about, so they are told once rather than five times a second. */
    private static final Map<UUID, Item> TOLD = new ConcurrentHashMap<>();

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % EVERY != 0) {
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                try {
                    look(player);
                } catch (Throwable e) {
                    PolymerPatcher.LOGGER.debug("Could not read {}'s armour", player.getGameProfile().name(), e);
                }
            }
        });
    }

    private static void look(ServerPlayer player) {
        ItemStack worn = ItemStack.EMPTY;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND) {
                continue;
            }
            ItemStack stack = player.getItemBySlot(slot);
            if (ArmourAbility.waitingOnAKeypress(player, stack)) {
                worn = stack;
                break;
            }
        }

        if (worn.isEmpty()) {
            clear(player);
            return;
        }

        // Said once, when it goes on, in the same breath the mod's own client would have said it
        if (TOLD.put(player.getUUID(), worn.getItem()) != worn.getItem()) {
            player.sendSystemMessage(Component.empty()
                .append(worn.getHoverName().copy().withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" has an ability. Crouch and press your swap-hands key to use it.")
                    .withStyle(ChatFormatting.GRAY)));
        }

        Float progress = fullness(worn);
        if (progress == null || !ConfigManager.config().entities.showArmourAbilityMeter) {
            hide(player);
            return;
        }

        boolean ready = progress >= 1.0F;
        ServerBossEvent bar = BARS.computeIfAbsent(player.getUUID(), id -> {
            ServerBossEvent made = new ServerBossEvent(UUID.randomUUID(), Component.empty(),
                BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS);
            made.addPlayer(player);
            return made;
        });
        bar.setProgress(Math.clamp(progress, 0.0F, 1.0F));
        bar.setColor(ready ? BossEvent.BossBarColor.WHITE : BossEvent.BossBarColor.PURPLE);
        bar.setName(Component.empty()
            .append(worn.getHoverName())
            .append(Component.literal(ready ? " - ready (crouch + swap hands)"
                    : " - " + Math.round(progress * 100) + "%")
                .withStyle(ready ? ChatFormatting.WHITE : ChatFormatting.GRAY)));
        bar.setVisible(true);
    }

    private static void hide(ServerPlayer player) {
        ServerBossEvent bar = BARS.get(player.getUUID());
        if (bar != null) {
            bar.setVisible(false);
        }
    }

    /** Called when a player leaves, so nothing is kept for somebody who is not there. */
    public static void forget(ServerPlayer player) {
        ServerBossEvent bar = BARS.remove(player.getUUID());
        if (bar != null) {
            bar.removeAllPlayers();
        }
        TOLD.remove(player.getUUID());
    }

    private static void clear(ServerPlayer player) {
        hide(player);
        TOLD.remove(player.getUUID());
    }

    /**
     * How full this item says it is, between nought and one, or null where it will not say.
     * <p>
     * The name is the mod's own: {@code getMeterProgress} is what Alex's Caves' HUD calls to draw the
     * gauge for the cloak of darkness. Anything that offers the same is read the same way, and anything
     * that does not is simply left without a gauge.
     */
    private static @Nullable Float fullness(ItemStack stack) {
        Method meter = meterMethod(stack.getItem().getClass());
        if (meter == null) {
            return null;
        }
        try {
            Object value = meter.invoke(null, stack);
            return value instanceof Float found ? found : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static final Map<Class<?>, Object> METERS = new HashMap<>();

    /** Stands for "this item keeps no gauge", since a map will not hold null. */
    private static final Object NONE = new Object();

    private static synchronized @Nullable Method meterMethod(Class<?> item) {
        Object found = METERS.computeIfAbsent(item, type -> {
            for (Method method : type.getMethods()) {
                if (method.getName().equals("getMeterProgress")
                    && java.lang.reflect.Modifier.isStatic(method.getModifiers())
                    && method.getReturnType() == float.class
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == ItemStack.class) {
                    return method;
                }
            }
            return NONE;
        });

        return found == NONE ? null : (Method) found;
    }
}
