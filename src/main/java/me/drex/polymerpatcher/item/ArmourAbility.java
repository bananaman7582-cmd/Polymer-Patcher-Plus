package me.drex.polymerpatcher.item;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.util.NativeClients;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets a client without the mod use a piece of armour that has an ability of its own.
 * <p>
 * Some modded armour does something when you press a key: Alex's Caves' cloak of darkness charges up
 * in the dark and, on a keypress, spends the charge to make you darkness incarnate. That key belongs to
 * the mod. It is bound in the mod's own controls, it sends the mod's own packet, and a client that does
 * not have the mod has neither - so the armour can be worn, charged and never once used.
 * <p>
 * The server does not need the mod's packet. What that packet does when it arrives is call a method on
 * the item, and the item is right here. So an input a plain client <em>can</em> send is used instead:
 * crouch and press the swap-hands key. Nothing is swapped when an ability answers; where none does, the
 * hands swap as they always have.
 * <p>
 * Which items have an ability is not written down anywhere here. An item is asked whether it has the
 * method the mod's own packet handler would have called, and if it has, it is called the same way.
 */
public final class ArmourAbility {

    private ArmourAbility() {
    }

    /**
     * The method a mod's key packet calls on the item. Alex's Caves' is
     * {@code KeybindUsingArmor.onKeyPacket(Entity, ItemStack, int)}, and the shape is the interesting
     * part rather than the interface: anything with a method of this name and shape is an item that
     * was waiting for a keypress.
     */
    private static final String KEY_PRESSED = "onKeyPacket";

    /**
     * Fires the ability of whatever the player is wearing that has one.
     *
     * @return whether anything answered, which is how the caller knows to keep the keypress
     */
    public static boolean use(ServerPlayer player) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot == EquipmentSlot.MAINHAND || slot == EquipmentSlot.OFFHAND) {
                continue;
            }

            if (fire(player, player.getItemBySlot(slot))) {
                return true;
            }
        }

        return false;
    }

    /**
     * Calls the item's own handler and works out whether it did anything.
     * <p>
     * It will do nothing at all most of the time - the cloak answers only in darkness, only with the
     * hood on, and only when it has charge - and there is no way to ask beforehand which time this is.
     * So it is called and the difference is looked at: an ability that fires changes the player or the
     * item it is on, every time, because that is what an ability is.
     */
    /**
     * Whether this is a piece of armour with an ability, on somebody with no other way to use it.
     * <p>
     * The same two questions {@link #fire} asks before it does anything, asked without doing anything -
     * so that what a player is told they can do and what they can actually do cannot come apart.
     */
    public static boolean waitingOnAKeypress(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty() || keyMethod(stack.getItem().getClass()) == null) {
            return false;
        }

        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null || !NativeClients.has(player, id.getNamespace());
    }

    private static boolean fire(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        Method pressed = keyMethod(stack.getItem().getClass());
        if (pressed == null) {
            return false;
        }

        // A player who has the mod has the mod's own key for this, and would rather their hands swapped
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id != null && NativeClients.has(player, id.getNamespace())) {
            return false;
        }

        ItemStack before = stack.copy();
        Set<?> effects = new HashSet<>(player.getActiveEffects());
        try {
            pressed.invoke(stack.getItem(), (Entity) player, stack, 0);
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("{} would not answer a keypress", id, e);
            return false;
        }

        return !ItemStack.matches(before, stack) || !effects.equals(new HashSet<>(player.getActiveEffects()));
    }

    private static final Map<Class<?>, Object> KEY_METHODS = new ConcurrentHashMap<>();

    /** Stands for "this item is not waiting for a keypress", since a map will not hold null. */
    private static final Object NONE = new Object();

    private static @Nullable Method keyMethod(Class<?> item) {
        Object found = KEY_METHODS.computeIfAbsent(item, type -> {
            for (Method method : type.getMethods()) {
                if (method.getName().equals(KEY_PRESSED) && method.getParameterCount() == 3
                    && method.getParameterTypes()[0].isAssignableFrom(ServerPlayer.class)
                    && method.getParameterTypes()[1] == ItemStack.class
                    && method.getParameterTypes()[2] == int.class) {
                    return method;
                }
            }
            return NONE;
        });

        return found == NONE ? null : (Method) found;
    }
}
