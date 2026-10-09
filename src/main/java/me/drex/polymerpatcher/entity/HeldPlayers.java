package me.drex.polymerpatcher.entity;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.util.NativeClients;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Holds a player in place the way the server means them to be held, on a client that cannot tell.
 * <p>
 * A player moves themselves: their client works out where they go and the server only checks the
 * result. So anything that slows or pins a player has to happen on their client too - and a client
 * without the mod never runs that code. A modded cobweb is shown to it as a harmless stand-in (carriers
 * that do things to players are kept out on purpose, see {@code AutomaticFactoryBlock#HURTS}), so walking
 * into one slowed the player on the server and nowhere that mattered. Webbed's webs were the case
 * reported: neither its web blocks nor the webs its spiders shoot held anybody.
 * <p>
 * What a vanilla client does obey is its own attributes and a change of motion, so the hold is put into
 * those: movement speed, jump strength and gravity scaled down, and - as the game itself does for a
 * player stuck in a block - their momentum taken away each tick. The walking speed in the abilities they
 * are sent is scaled to match. A client uses that figure for one thing only, how far the view zooms with
 * speed, and without it a held player's view closed in as if they had Slowness.
 * <p>
 * Global for blocks: a block from a mod the player lacks that sticks them the vanilla way (the cobweb's
 * {@code makeStuckInBlock}) is held here whichever mod it comes from. Anything that holds players its own
 * way needs a line in {@link #afterTick} saying how to ask it, as Webbed's shot webs have.
 */
public final class HeldPlayers {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("polymer-patcher", "held");
    /** Ticks a hold outlasts its last request, so one tick that happened not to ask does not let go. */
    private static final int GRACE = 2;

    private static final Map<ServerPlayer, Held> HELD = new WeakHashMap<>();

    private HeldPlayers() {
    }

    /** How much of their own movement a held player keeps, each from 0 (none) to 1 (all of it). */
    public record Hold(double walk, double jump, double gravity, boolean stopMomentum) {
        Hold strongest(Hold other) {
            return new Hold(Math.min(walk, other.walk), Math.min(jump, other.jump), Math.min(gravity, other.gravity),
                stopMomentum || other.stopMomentum);
        }
    }

    private static final class Held {
        Hold wanted;
        Hold applied;
        int idle;
    }

    /** A block has stuck this player the vanilla way; {@code multiplier} is what it scales their movement by. */
    public static void stuck(ServerPlayer player, BlockState state, Vec3 multiplier) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        // A client does this itself for a block it has: every vanilla one, and a mod's when it has the mod
        if (id == null || "minecraft".equals(id.getNamespace()) || NativeClients.carries(player, id.getNamespace())) {
            return;
        }
        double vertical = Math.min(multiplier.y, 1.0);
        request(player, new Hold(Math.min(Math.max(multiplier.x, multiplier.z), 1.0), vertical, vertical, true));
    }

    /** Holds the player for this tick. Asked again every tick for as long as they should stay held. */
    public static void request(ServerPlayer player, Hold hold) {
        Held held = HELD.computeIfAbsent(player, p -> new Held());
        held.wanted = held.wanted == null ? hold : held.wanted.strongest(hold);
    }

    /** At the end of each of the player's own ticks, after the blocks they are in have had their say. */
    public static void afterTick(ServerPlayer player) {
        Webbed.check(player);

        Held held = HELD.get(player);
        if (held == null) {
            return;
        }
        Hold wanted = held.wanted;
        held.wanted = null;
        if (wanted != null) {
            held.idle = 0;
            boolean changed = !wanted.equals(held.applied);
            if (changed) {
                apply(player, wanted);
                held.applied = wanted;
            }
            // Stuck in a block the game zeroes a player's motion every tick. A hold that only stops them
            // walking takes away the run-up they came in with, once
            if (wanted.stopMomentum() || changed && wanted.walk() == 0.0) {
                player.connection.send(new ClientboundSetEntityMotionPacket(player.getId(), Vec3.ZERO));
            }
        } else if (++held.idle > GRACE) {
            HELD.remove(player);
            apply(player, null);
        }
    }

    private static void apply(ServerPlayer player, Hold hold) {
        scale(player, Attributes.MOVEMENT_SPEED, hold == null ? 1.0 : hold.walk());
        scale(player, Attributes.JUMP_STRENGTH, hold == null ? 1.0 : hold.jump());
        scale(player, Attributes.GRAVITY, hold == null ? 1.0 : hold.gravity());

        // Only ever sent: the player's own abilities are left exactly as they were
        Abilities sent = new Abilities();
        sent.apply(player.getAbilities().pack());
        if (hold != null) {
            sent.setWalkingSpeed((float) (sent.getWalkingSpeed() * hold.walk()));
        }
        player.connection.send(new ClientboundPlayerAbilitiesPacket(sent));
    }

    private static void scale(ServerPlayer player, Holder<Attribute> attribute, double factor) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return;
        }
        instance.removeModifier(ID);
        if (factor < 1.0) {
            instance.addTransientModifier(new AttributeModifier(ID, factor - 1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    /**
     * Webbed's shot webs, which pin whoever they catch from their own code rather than the game's: no
     * walking and no jumping, falling left alone. It answers both questions for anyone who asks.
     */
    private static final class Webbed {
        private static final MethodHandle PREVENT_MOVEMENT = find("shouldPreventMovement");
        private static final MethodHandle PREVENT_JUMPING = find("shouldPreventJumping");
        private static boolean broken;

        static void check(ServerPlayer player) {
            if (PREVENT_MOVEMENT == null || PREVENT_JUMPING == null || broken || NativeClients.carries(player, "web")) {
                return;
            }
            try {
                boolean pinned = (boolean) PREVENT_MOVEMENT.invokeExact((LivingEntity) player);
                boolean grounded = (boolean) PREVENT_JUMPING.invokeExact((LivingEntity) player);
                if (pinned || grounded) {
                    request(player, new Hold(pinned ? 0.0 : 1.0, grounded ? 0.0 : 1.0, 1.0, false));
                }
            } catch (Throwable e) {
                broken = true;
                PolymerPatcher.LOGGER.warn("Could not ask Webbed whether a player is caught in a web; its webs will not hold players without the mod", e);
            }
        }

        private static MethodHandle find(String name) {
            if (!FabricLoader.getInstance().isModLoaded("web")) {
                return null;
            }
            try {
                Class<?> web = Class.forName("potatowolfie.web.entity.custom.SpiderWebEntity");
                return MethodHandles.publicLookup().findStatic(web, name, MethodType.methodType(boolean.class, LivingEntity.class));
            } catch (ReflectiveOperationException | LinkageError e) {
                PolymerPatcher.LOGGER.warn("Webbed's webs cannot be asked who they hold; they will not hold players without the mod", e);
                return null;
            }
        }
    }
}
