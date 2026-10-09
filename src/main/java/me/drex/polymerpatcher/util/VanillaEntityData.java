package me.drex.polymerpatcher.util;

import eu.pb4.polymer.core.api.entity.PolymerEntity;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.resources.ResourceHelper;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Puts an entity update into the shape a vanilla client reads it in.
 * <p>
 * Tracked data goes over the wire as a numbered list, and the numbers are handed out at start-up: each
 * entity class takes the next few after its superclass. A mod that adds one to {@code LivingEntity}
 * therefore does not just add a number at the end - it takes number 15, which on a vanilla client is
 * the player's absorption, and pushes every field below it down by one. The client is not told any of
 * this. It reads number 15 as absorption and gets whatever the mod put there.
 * <p>
 * Which is a crash, not a glitch: the client checks the value against the type it expects and throws
 * out the connection when they disagree. Alex's Mobs adds one such field, so every player on that
 * server was being sent a shifted list.
 * <p>
 * So each entry is put back on the number vanilla would have given it, and entries belonging to fields
 * vanilla has no idea about are dropped. What counts as vanilla is read from the game's own jar rather
 * than assumed, because by the time this runs the loaded classes already carry the additions.
 */
public final class VanillaEntityData {

    private static final String SYNCHED_ENTITY_DATA = "net/minecraft/network/syncher/SynchedEntityData";
    private static final String SERIALIZERS = "net/minecraft/network/syncher/EntityDataSerializers";

    /**
     * The actual serializer objects declared by Minecraft's untouched class file.
     * <p>
     * The loaded {@link EntityDataSerializers} class is not a trustworthy list: mixins can add fields
     * to it before this class looks, which is how a mod serializer at wire id 45 was mistaken for a
     * vanilla one and disconnected clients that did not have the mod. The names come from the client
     * jar, then only those named fields are resolved on the live class.
     */
    private static final Set<EntityDataSerializer<?>> VANILLA_SERIALIZER_TYPES = findVanillaSerializers();

    /**
     * One past the highest serializer index the game itself uses. Read off the game's own fields
     * rather than counted by hand, so a version that adds a serializer needs nothing done here.
     */
    private static final int SERIALIZER_LIMIT = findSerializerLimit();

    /**
     * 255 ends the entry list. An entry carrying it would end the packet early and leave the rest of
     * the entries to be read as something else entirely.
     */
    private static final int MAX_DATA_ID = 254;

    private static final Map<Layout, int[]> INDEX_MAPS = new ConcurrentHashMap<>();

    /**
     * What each client index is supposed to hold, for the layouts that have been worked out.
     * <p>
     * The last line of defence, and the only one that does not depend on getting the arithmetic right.
     * Every disconnect this mod has caused looked the same from the client's side - a value of one kind
     * arriving where it keeps another - and every one of them was a different mistake in working out
     * which number to use. Checking the value against the slot catches all of them at once, whatever
     * the cause, because it asks the question the client is about to ask.
     */
    private static final Map<Layout, String[]> EXPECTED_BY_LAYOUT = new ConcurrentHashMap<>();

    private static final Map<String, Integer> VANILLA_FIELD_COUNTS = new ConcurrentHashMap<>();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private VanillaEntityData() {
    }

    /**
     * Whether this value would land where the client keeps something of another kind.
     * <p>
     * Only says yes when both sides are known. A slot nothing is known about is left alone. Values
     * using a serializer the game does not ship have already been removed by {@link #translate}.
     */
    private static boolean wouldNotFit(SynchedEntityData.DataValue<?> value, String[] expected) {
        int id = value.id();
        if (id < 0 || id >= expected.length) {
            return false;
        }

        String wanted = expected[id];
        if (wanted == null || wanted.isEmpty()) {
            return false;
        }

        String carried = SERIALIZER_NAMES.get(value.serializer());
        return carried != null && !carried.equals(wanted);
    }

    /** Whether this is a class the game ships, rather than one a mod added. */
    private static boolean isVanillaClass(Class<?> type) {
        return type.getName().startsWith("net.minecraft.");
    }

    /**
     * The entries of {@code values} as a vanilla client should receive them.
     * <p>
     * The list that came in is handed straight back when nothing needed doing, which is the usual case
     * on a server carrying no mods that add tracked data.
     *
     * @param entity the entity being described, when it is known - without it only the entries that
     *               are unreadable on their own can be caught, since which number means what is a
     *               property of the entity's class
     */
    public static List<SynchedEntityData.DataValue<?>> sanitize(List<SynchedEntityData.DataValue<?>> values, @Nullable Entity entity, @Nullable ServerPlayer player) {
        if (values == null || values.isEmpty()) {
            return values;
        }

        // A client carrying the same mods numbers its own fields exactly as this server does. Renaming
        // them to what vanilla would have called them hands it the wrong fields and gets it thrown off
        // the server, so the one client that needs no help is the one this must not touch.
        //
        // Only where the client is looking at the real entity, though. A mob Polymer stands in for is
        // sent as something vanilla whoever is watching, so its update has to be trimmed to fit that
        // stand-in no matter which mods the player has - leaving a modded mob's own fields in was
        // handing a marker field numbers it had no room for, and the player was disconnected on the
        // spot
        if (NativeClients.hasAll(player) && sentAsItself(entity)) {
            return values;
        }

        // Nothing is known about this player yet, and both answers are wrong in that state. A client
        // says what it carries a second or three after it arrives; the displays around its spawn go out
        // the instant it does. Numbering those for a vanilla client kills a modded one, and numbering
        // them for a modded client kills a vanilla one - the choice is between two disconnects.
        //
        // So neither is chosen. Only the eight fields every entity has are sent, which are the eight
        // every client numbers the same way whatever it has installed. The rest waits for the next
        // update, by which time the client will have spoken. What that costs is an entity briefly
        // missing its pose; what it saves is the player.
        if (!NativeClients.settled(player)) {
            return apply(values, entity, universalFields(), null);
        }

        // Borrowed Echo's disguise is intentionally a different concrete entity on the wire. Its own
        // tracked fields describe encounter phases and disguise state; a cow or player interprets
        // those same numbers as an entirely different schema. Keep only Entity's universal fields,
        // then supply the one player-specific visual default we need. Creature-specific fields retain
        // their safe vanilla defaults and locomotion still comes from ordinary movement packets.
        if (entity != null && me.drex.polymerpatcher.compat.borrowedecho.BorrowedEchoCompat.usesVanillaCarrier(entity)) {
            List<SynchedEntityData.DataValue<?>> safe = apply(values, null, universalFields(), null);
            if (me.drex.polymerpatcher.compat.borrowedecho.BorrowedEchoCompat.usesPlayerCarrier(entity)) {
                safe = withPlayerSkinLayers(safe, player);
            }
            return safe;
        }

        // Only entities the game itself ships get renumbered.
        //
        // Renumbering answers one problem: a mod that adds tracked data to a class the client also has
        // - Alex's Mobs adds a field to LivingEntity - shifts every field below it, so the numbers this
        // server uses for a cow are not the numbers a vanilla client reads them at. Putting them back
        // is only meaningful for a class the client actually has.
        //
        // A mod's own mob is never that. The client is being sent a stand-in for it - a marker with
        // nothing but the eight fields every entity has - and Polymer has already rewritten the list to
        // suit that stand-in. Renumbering it to the real mob's layout hands the client a field number
        // its stand-in has no room for, which is not a glitch but an outright disconnect
        int[] map;
        String[] expected = null;
        if (entity == null) {
            // A packet with no entity behind it belongs to something Polymer invented - one of the
            // displays or interactions this mod draws everything with, which exist only on the wire.
            // Nothing above was in a position to correct those: there is no class to look a layout up
            // from. But a mod that adds tracked data to Entity itself shifts every entity there is,
            // displays included, and that shift is the same whatever the entity turns out to be - so
            // it can be undone without knowing which one this is.
            //
            // Alex's Caves is what found this. It adds four fields to Entity, so a display's right
            // rotation went out numbered where a vanilla client keeps an interpolation delay. The
            // client read a number as a rotation, threw the packet out, and the player with it - the
            // moment anything this mod draws so much as moved.
            map = shiftForEntityLevel(player);
        } else {
            Class<?> vanillaLayout = vanillaLayoutClass(entity);
            Set<String> clientHas = NativeClients.shiftingModsOf(player);
            map = vanillaLayout != null && sentAsItself(entity)
                ? indexMap(vanillaLayout, clientHas)
                : null;

            if (map != null) {
                // Worked out beside the map it belongs to, so what the client keeps at each number is
                // known by the time anything is written there
                expected = EXPECTED_BY_LAYOUT.get(new Layout(vanillaLayout, clientHas));
            }

            if (map == null) {
                // Being shown as something else - a modded mob sent as the display that stands in for
                // it. There is no vanilla layout for what it really is, which is why nothing was done
                // here before; but the fields on the wire are the stand-in's own, and those are numbered
                // the way this server numbers everything. A mod that added fields to Entity shifted the
                // stand-in too, so the same correction applies
                map = shiftForEntityLevel(player);
            }
        }

        return apply(values, entity, map, expected);
    }

    /** Adds the vanilla player's model-parts byte at the exact index this receiving client expects. */
    private static List<SynchedEntityData.DataValue<?>> withPlayerSkinLayers(
        List<SynchedEntityData.DataValue<?>> values, @Nullable ServerPlayer player) {
        try {
            Field field = net.minecraft.world.entity.Avatar.class.getDeclaredField("DATA_PLAYER_MODE_CUSTOMISATION");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            EntityDataAccessor<Byte> accessor = (EntityDataAccessor<Byte>) field.get(null);

            int[] map = indexMap(net.minecraft.world.entity.Avatar.class, NativeClients.shiftingModsOf(player));
            int id = accessor.id();
            if (id < 0 || id >= map.length || map[id] < 0) {
                return values;
            }
            int clientId = map[id];

            List<SynchedEntityData.DataValue<?>> result = new ArrayList<>(values.size() + 1);
            for (SynchedEntityData.DataValue<?> value : values) {
                if (value.id() != clientId) {
                    result.add(value);
                }
            }
            // Every skin layer (hat, jacket, sleeves and trousers), matching an ordinary player.
            result.add(new SynchedEntityData.DataValue<>(clientId, EntityDataSerializers.BYTE, (byte) 0x7f));
            return result;
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Could not enable Borrowed Echo player skin layers", e);
            return values;
        }
    }

    /** Runs one numbering over a list of updates, dropping whatever has no place under it. */
    private static List<SynchedEntityData.DataValue<?>> apply(List<SynchedEntityData.DataValue<?>> values,
                                                             @Nullable Entity entity, int @Nullable [] map,
                                                             String @Nullable [] expected) {
        List<SynchedEntityData.DataValue<?>> rewritten = null;
        for (int i = 0; i < values.size(); i++) {
            SynchedEntityData.DataValue<?> value = values.get(i);
            SynchedEntityData.DataValue<?> kept = translate(value, map, entity);

            // The one check that does not rest on having got the numbering right. Whatever number this
            // ended up with, the client is about to compare what arrives against what it keeps there -
            // so the same comparison is made first, and a value that would fail it is dropped instead
            // of sent. A missing field is a mob that looks briefly wrong; a wrong one is everybody on
            // a modded server being thrown off it.
            if (kept != null && expected != null && wouldNotFit(kept, expected)) {
                report(value, entity, "the client keeps a different kind of value at index " + kept.id());
                kept = null;
            }

            if (kept == value) {
                if (rewritten != null) {
                    rewritten.add(value);
                }
                continue;
            }

            if (rewritten == null) {
                rewritten = new ArrayList<>(values.subList(0, i));
            }
            if (kept != null) {
                rewritten.add(kept);
            }
        }

        return clampToEntity(rewritten != null ? rewritten : values, entity);
    }

    /**
     * The Entity-level correction, but only for a client that needs it.
     * <p>
     * Both places this is used are reached whatever the player has. One is a display Polymer invented,
     * which has no entity behind it to ask about; the other is a modded mob being sent as the stand-in
     * that draws it, where the guard at the top of this method does not apply because the mob is
     * plainly not being sent as itself. Neither of those says anything about the client - and the
     * correction they were applying regardless is only right for a client that lacks the mods.
     * <p>
     * A player who has Alex's Caves numbers every entity from twelve, displays and stand-ins included.
     * Shifting those down to eight for them put an interpolation duration where their client keeps one
     * of the mod's own floats, and ended the connection on the first display that moved - which was the
     * same disconnect this correction exists to prevent, handed to the opposite half of the players.
     */
    private static int @Nullable [] shiftForEntityLevel(@Nullable ServerPlayer player) {
        Set<String> adders = TrackedDataMods.modsAddingTo(Entity.class);
        Set<String> has = NativeClients.shiftingModsOf(player);
        if (!adders.isEmpty() && has.containsAll(adders)) {
            // They number entities the way this server does; there is nothing to put back
            return null;
        }
        if (!adders.isEmpty() && !java.util.Collections.disjoint(adders, has)) {
            // Some of the mods that add to Entity and not others: only the others' fields come out
            Set<String> kept = new java.util.HashSet<>(adders);
            kept.retainAll(has);
            int[] partial = PARTIAL_ENTITY_SHIFTS.computeIfAbsent(Set.copyOf(kept),
                k -> java.util.Optional.ofNullable(entityLevelShiftKeeping(k))).orElse(null);
            if (partial != null) {
                return partial;
            }
        }
        return entityLevelShiftMap();
    }

    private static final Map<Set<String>, java.util.Optional<int[]>> PARTIAL_ENTITY_SHIFTS = new java.util.concurrent.ConcurrentHashMap<>();

    /** The Entity-level correction for a client that has these of the mods adding to Entity, or null if unsure. */
    private static int @Nullable [] entityLevelShiftKeeping(Set<String> kept) {
        try {
            int vanillaCount = vanillaFieldCount(Entity.class);
            int allocated = allocatedCount(Entity.class);
            if (vanillaCount < 0 || vanillaCount == Integer.MAX_VALUE || allocated < 0) {
                return null;
            }
            Set<Integer> dropped = droppedFor(Entity.class, kept, vanillaCount, 0, allocated);
            if (dropped == null) {
                return null;
            }
            int[] map = new int[MAX_DATA_ID + 1];
            int removed = 0;
            for (int id = 0; id <= MAX_DATA_ID; id++) {
                if (dropped.contains(id)) {
                    map[id] = -1;
                    removed++;
                } else {
                    map[id] = id - removed;
                }
            }
            return map;
        } catch (Throwable e) {
            return null;
        }
    }

    private static volatile int @Nullable [] universal;

    /**
     * The fields every entity has, numbered the way every client numbers them.
     * <p>
     * These come before anything a mod adds, so they sit at the same indices whatever is installed.
     * Sending only these says less than the truth but never says anything false, which is what is
     * wanted while it is still unknown who is listening.
     */
    private static int[] universalFields() {
        int[] map = universal;
        if (map != null) {
            return map;
        }

        int count = vanillaFieldCount(Entity.class);
        if (count <= 0 || count == Integer.MAX_VALUE) {
            // Unreadable for some reason; the game has had eight for years and too few is harmless here
            count = 8;
        }

        map = new int[count];
        for (int i = 0; i < count; i++) {
            map[i] = i;
        }
        universal = map;
        return map;
    }

    /**
     * The vanilla class whose tracked-data layout the receiving client constructs.
     * <p>
     * The concrete server class is not always the class on the wire. Carpet's fake player is the
     * important example: {@code EntityPlayerMPFake} is a mod class, but it extends {@link ServerPlayer}
     * and is spawned to everybody as the ordinary player entity type. Treating the concrete class as
     * non-vanilla skipped the Alex's Mobs index correction, sent the avatar customisation byte at the
     * vanilla player's absorption-float slot, and disconnected the observing player immediately.
     * <p>
     * Walking to the first game-owned superclass preserves the real wire layout for wrapper subclasses
     * without pretending a custom mob is vanilla. Custom mobs are excluded when Polymer reports that
     * the receiving client is constructing a different stand-in type.
     */
    private static @Nullable Class<?> vanillaLayoutClass(@Nullable Entity entity) {
        if (entity == null) {
            return null;
        }

        for (Class<?> type = entity.getClass(); type != null && Entity.class.isAssignableFrom(type); type = type.getSuperclass()) {
            if (isVanillaClass(type)) {
                return type;
            }
        }
        return null;
    }

    /**
     * Whether the client is being shown this entity as itself, rather than as a stand-in.
     * <p>
     * This is the question the whole disconnect turned on. Leaving an update untranslated is only safe
     * while the client is looking at the real mob, and that is decided somewhere else entirely - so it
     * is asked here rather than guessed at. Asking it directly is what keeps the two answers from ever
     * drifting apart again.
     */
    private static boolean sentAsItself(@Nullable Entity entity) {
        if (entity == null) {
            return true;
        }
        try {
            PolymerEntity handler = PolymerEntity.get(entity);
            return handler == null || handler.getPolymerEntityType(PacketContext.get()) == entity.getType();
        } catch (Throwable e) {
            // Unable to tell, so assume the stand-in and translate - the safe way round
            return false;
        }
    }

    /** Field counts by the type they belong to, since working one out builds a whole entity. */
    private static final Map<EntityType<?>, Integer> FIELD_COUNTS = new ConcurrentHashMap<>();

    /** Types already complained about, so a field dropped every tick is mentioned once. */
    private static final Set<EntityType<?>> REPORTED_OVERFLOW = ConcurrentHashMap.newKeySet();

    /**
     * Drops any field numbered past the end of the entity the client was told to build.
     * <p>
     * A client sizes an entity's field table from its type and then writes each update straight into
     * it, so a number past the end is not a wrong value - it is an index out of bounds, thrown while
     * handling the packet, which ends the connection. There is no reading of such a field that could
     * have been right, so dropping it costs whatever that one field was and keeps the player on the
     * server.
     * <p>
     * This is a backstop rather than a fix: everything upstream is supposed to agree on which type the
     * client is being sent. When they disagree - and the entity type a player is shown is decided
     * separately from the fields they are sent - this is what stands between a mistake and a kick, and
     * it names the type it happened on so the disagreement can be found.
     */
    private static List<SynchedEntityData.DataValue<?>> clampToEntity(List<SynchedEntityData.DataValue<?>> values, @Nullable Entity entity) {
        if (entity == null) {
            return values;
        }

        EntityType<?> type = clientType(entity);
        if (type == null) {
            return values;
        }

        int count = FIELD_COUNTS.computeIfAbsent(type, VanillaEntityData::fieldCount);
        if (count <= 0) {
            return values;
        }

        List<SynchedEntityData.DataValue<?>> kept = null;
        for (int i = 0; i < values.size(); i++) {
            SynchedEntityData.DataValue<?> value = values.get(i);
            if (value.id() < count) {
                if (kept != null) {
                    kept.add(value);
                }
                continue;
            }

            if (kept == null) {
                kept = new ArrayList<>(values.subList(0, i));
            }

            if (REPORTED_OVERFLOW.add(type)) {
                PolymerPatcher.LOGGER.warn(
                    "Dropped tracked data index {} bound for {}, which a client sizes to {} field(s). "
                        + "Sending it would have thrown the player off the server. Reported once per type.",
                    value.id(), BuiltInRegistries.ENTITY_TYPE.getKey(type), count);
            }
        }

        return kept != null ? kept : values;
    }

    /**
     * The type the client will build for this entity, which is the stand-in where there is one.
     */
    @Nullable
    private static EntityType<?> clientType(Entity entity) {
        try {
            PolymerEntity handler = PolymerEntity.get(entity);
            if (handler == null) {
                return entity.getType();
            }
            return handler.getPolymerEntityType(PacketContext.get());
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * How many fields a client gives an entity of this type, asked of Polymer, which builds one to find
     * out. Zero when it cannot be worked out, which turns the check off rather than guessing.
     */
    private static int fieldCount(EntityType<?> type) {
        try {
            Object array = exampleTrackedData(type);
            return array == null ? 0 : java.lang.reflect.Array.getLength(array);
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Could not size {}", type, e);
            return 0;
        }
    }

    /** Looked up once; this is asked for every entity type on the server. */
    private static volatile java.lang.reflect.@Nullable Method exampleTrackedData;

    private static Object exampleTrackedData(EntityType<?> type) throws Exception {
        java.lang.reflect.Method method = exampleTrackedData;
        if (method == null) {
            Class<?> helpers = Class.forName("eu.pb4.polymer.common.impl.entity.InternalEntityHelpers");
            method = helpers.getMethod("getExampleTrackedDataOfEntityType", EntityType.class);
            method.setAccessible(true);
            exampleTrackedData = method;
        }
        return method.invoke(null, type);
    }

    /**
     * Asks after every entity type once, on one thread, before anybody can connect.
     * <p>
     * Polymer keeps what it learns about an entity type in a plain map with no lock on it, and fills
     * that map the first time anything asks about a type. Asking happens on whichever thread needs the
     * answer - a player's connection thread while an update is being encoded, the server thread while a
     * display is being built - and two of them arriving together while the map is growing corrupts it.
     * That is not a glitch either: it comes back as {@code Index -1 out of bounds}, thrown from inside
     * the map, and it took the whole server down with it.
     * <p>
     * Nothing here can add a lock to somebody else's map. What it can do is make sure the map is never
     * written to again once players are about: every answer is worked out here, in one pass, on one
     * thread, so afterwards every one of those calls is a plain read and there is nothing left to race.
     */
    public static void prewarm() {
        int warmed = 0;
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            try {
                exampleTrackedData(type);
                FIELD_COUNTS.computeIfAbsent(type, VanillaEntityData::fieldCount);
                warmed++;
            } catch (Throwable e) {
                // A type that will not build an example is one Polymer will not cache either, so it
                // cannot be the one that races. Costing it the warm-up costs nothing else
                PolymerPatcher.LOGGER.debug("Could not warm up {}", type, e);
            }
        }

        // Worked out here rather than when the first packet needs it. Built lazily it was not ready
        // until several seconds after the first player joined - and everything this mod draws that was
        // sent in the meantime went out numbered the way this server numbers it, which is exactly what
        // a client without the mods cannot read
        entityLevelShiftMap();

        // TEMPORARY DIAGNOSTIC - builds the layout a modless client gets, so its numbers are in the
        // log at boot rather than only after somebody joins and is disconnected by it
        try {
            indexMap(net.minecraft.server.level.ServerPlayer.class, Set.of());
            indexMap(net.minecraft.world.entity.item.ItemEntity.class, Set.of());
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.warn("Could not prewarm the tracked-data layouts", e);
        }

        PolymerPatcher.LOGGER.info("Worked out the tracked data of {} entity type(s) up front, so nothing has to while players are connected", warmed);
    }

    /**
     * The same entry, the entry renumbered, or null when it has no place on a vanilla client.
     */
    private static SynchedEntityData.@Nullable DataValue<?> translate(SynchedEntityData.DataValue<?> value, int[] map, @Nullable Entity entity) {
        if (value.id() < 0 || value.id() > MAX_DATA_ID) {
            report(value, entity, "its index is outside what the packet can carry");
            return null;
        }

        int serializerId = EntityDataSerializers.getSerializedId(value.serializer());
        if ((!VANILLA_SERIALIZER_TYPES.isEmpty() && !VANILLA_SERIALIZER_TYPES.contains(value.serializer()))
            || serializerId < 0 || serializerId >= SERIALIZER_LIMIT) {
            report(value, entity, "its serializer (" + value.serializer().getClass().getName() + ", index " + serializerId + ") is not one a vanilla client has");
            return null;
        }

        if (map == null) {
            return value;
        }

        int vanillaId = value.id() < map.length ? map[value.id()] : -1;
        if (vanillaId < 0) {
            report(value, entity, "vanilla has no such field on this entity");
            return null;
        }
        if (vanillaId == value.id()) {
            return value;
        }

        return rewrite(value, vanillaId);
    }

    @SuppressWarnings("unchecked")
    private static <T> SynchedEntityData.DataValue<T> rewrite(SynchedEntityData.DataValue<T> value, int id) {
        return new SynchedEntityData.DataValue<>(id, value.serializer(), value.value());
    }

    /**
     * For one entity class, what each of its tracked data numbers is called on a vanilla client, or -1
     * where vanilla has no such field.
     * <p>
     * Built by walking the class from {@code Entity} downwards. Each class hands its numbers out in
     * order, and the game's own jar says how many of them belong to the game; whatever a class has
     * beyond that count was added by a mod, and takes no number at all on a client that hasn't got it.
     */
    /** Worked out once; every entity on the server shares it. */
    private static volatile int @Nullable [] entityLevelShift;
    private static volatile boolean entityLevelShiftKnown;

    /**
     * What the numbers mean when all that is known is that a mod moved them.
     * <p>
     * Vanilla hands {@code Entity} a fixed number of fields and every class below it carries on from
     * there, so a mod adding tracked data to {@code Entity} itself pushes every field of every entity
     * in the game up by however many it added. That makes the correction universal: the game's own
     * eight stay where they are, the mod's own take no number at all on a client that hasn't got the
     * mod, and everything after them comes back down by the same amount - true of a display, an
     * interaction, a mob, anything.
     *
     * @return the mapping, or null when nothing has been added to {@code Entity} and none is needed
     */
    private static int @Nullable [] entityLevelShiftMap() {
        if (entityLevelShiftKnown) {
            return entityLevelShift;
        }

        int[] built = null;
        try {
            int vanillaCount = vanillaFieldCount(Entity.class);
            int allocated = allocatedCount(Entity.class);
            // Failing that, the first number a direct subclass of Entity was given says the same
            // thing: whatever sits between the game's own Entity fields and it was added by a mod.
            // Counting Entity's declared fields is deliberately not the fallback - that is the count
            // that reads as unchanged here, and trusting it is what let this through in the first place
            int shift = allocated >= 0
                ? allocated - vanillaCount
                : firstSubclassId() - vanillaCount;

            if (shift > 0 && vanillaCount >= 0) {
                built = new int[MAX_DATA_ID + 1];
                for (int id = 0; id <= MAX_DATA_ID; id++) {
                    if (id < vanillaCount) {
                        built[id] = id;
                    } else if (id < vanillaCount + shift) {
                        // One of the mod's own, which a client without it has nowhere to put
                        built[id] = -1;
                    } else {
                        built[id] = id - shift;
                    }
                }
                PolymerPatcher.LOGGER.info("A mod has added {} field(s) to every entity; anything drawn by this mod is numbered back down for players without it", shift);
            }
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.warn("Could not work out how far mods have shifted entity fields", e);
        }

        entityLevelShift = built;
        entityLevelShiftKnown = true;
        return built;
    }

    /**
     * The first tracked-data number handed to a class that sits directly below {@code Entity}.
     * <p>
     * Everything {@code Entity} has was handed out before it, so this is where the game's own fields
     * end and anything a mod added to {@code Entity} has already been counted. Read from a display
     * because this mod draws with them, so the class is certain to have been set up by the time
     * anything asks.
     */
    private static int firstSubclassId() {
        int lowest = Integer.MAX_VALUE;
        for (int id : declaredIds(net.minecraft.world.entity.Display.class)) {
            lowest = Math.min(lowest, id);
        }
        return lowest == Integer.MAX_VALUE ? -1 : lowest;
    }

    /** The game's own id allocator, looked up once. */
    private static volatile java.lang.reflect.@Nullable Method allocatedCountMethod;
    private static volatile @Nullable Object idRegistry;
    private static volatile boolean idRegistryChecked;

    /**
     * How many tracked-data numbers a class has actually been given, mods included.
     * <p>
     * Counting the accessor fields a class declares is not the same question, and the difference is
     * what let this through. Mixin merges a mixin's own static fields into the class it targets, so a
     * mod that declares its accessors inside its mixin does end up declaring them on - say -
     * {@code LivingEntity}, and reflection finds them. A mod that keeps them in a holder class of its
     * own and only calls {@code defineId} from the mixin declares nothing anywhere reflection looks.
     * Alex's Caves is the second kind: its four fields live in {@code CitadelSyncedData}, so
     * {@code Entity} appeared unchanged while every entity in the game had moved up by four.
     * <p>
     * The game itself keeps the real count, because it is what hands the numbers out.
     *
     * @return the count, or -1 when the game's allocator could not be reached
     */
    private static int allocatedCount(Class<?> type) {
        if (!idRegistryChecked) {
            synchronized (VanillaEntityData.class) {
                if (!idRegistryChecked) {
                    try {
                        Field field = SynchedEntityData.class.getDeclaredField("ID_REGISTRY");
                        field.setAccessible(true);
                        Object registry = field.get(null);
                        allocatedCountMethod = registry.getClass().getMethod("getCount", Class.class);
                        allocatedCountMethod.setAccessible(true);
                        idRegistry = registry;
                    } catch (Throwable e) {
                        PolymerPatcher.LOGGER.warn("Could not reach the game's tracked data allocator; fields a mod added through a holder class will not be noticed", e);
                    }
                    idRegistryChecked = true;
                }
            }
        }

        java.lang.reflect.Method method = allocatedCountMethod;
        Object registry = idRegistry;
        if (method == null || registry == null) {
            return -1;
        }
        try {
            return (int) method.invoke(registry, type);
        } catch (Throwable e) {
            return -1;
        }
    }

    /**
     * The map for one entity class as one particular client would number it.
     * <p>
     * Kept apart by the mods the client has, because the answer genuinely differs. A player with none
     * of them numbers their fields the way the game ships; a player with some numbers them the way the
     * game ships plus whatever those mods added, and renumbering them down to bare vanilla is wrong by
     * exactly the number of fields they do have.
     */
    private record Layout(Class<?> entityClass, Set<String> clientHas) {
    }

    private static int[] indexMap(Class<?> entityClass, Set<String> clientHas) {
        return INDEX_MAPS.computeIfAbsent(new Layout(entityClass, clientHas), VanillaEntityData::buildIndexMap);
    }

    /**
     * The numbers this class handed to mods the client does not have, or null if they cannot be told apart
     * for certain - every mod's fields found, every one inside this class, and every number accounted for.
     */
    private static @org.jspecify.annotations.Nullable Set<Integer> droppedFor(Class<?> type, Set<String> clientHas,
                                                                             int vanillaCount, int firstId, int serverCount) {
        Map<String, List<Integer>> byMod = TrackedDataMods.idsAddedTo(type);
        if (byMod.isEmpty()) {
            return unsure(type, "no mod's fields were found", byMod);
        }
        int from = firstId + vanillaCount;
        int to = firstId + serverCount;

        // Every number that could be read back, and the one mod - if any - whose numbers could not be
        Set<Integer> known = new java.util.HashSet<>();
        Map<String, Set<Integer>> owned = new HashMap<>();
        String unread = null;
        int unreadCount = 0;
        for (Map.Entry<String, List<Integer>> mod : byMod.entrySet()) {
            Set<Integer> mine = owned.computeIfAbsent(mod.getKey(), k -> new java.util.HashSet<>());
            for (int id : mod.getValue()) {
                if (id < 0) {
                    if (unread != null && !unread.equals(mod.getKey())) {
                        return unsure(type, "more than one mod keeps its numbers where they cannot be read back", byMod);
                    }
                    unread = mod.getKey();
                    unreadCount++;
                    continue;
                }
                if (id < from || id >= to || !known.add(id)) {
                    return unsure(type, "number " + id + " is not one of those mods were given, " + from + " to " + (to - 1), byMod);
                }
                mine.add(id);
            }
        }

        // What is left of the class's numbers is the unread mod's, as long as it is exactly as many as it asked for
        Set<Integer> rest = new java.util.HashSet<>();
        for (int id = from; id < to; id++) {
            if (!known.contains(id)) {
                rest.add(id);
            }
        }
        if (unread != null) {
            if (rest.size() != unreadCount) {
                return unsure(type, rest.size() + " numbers unaccounted for, but " + unread + " asked for " + unreadCount, byMod);
            }
            owned.get(unread).addAll(rest);
        } else if (!rest.isEmpty()) {
            return unsure(type, rest.size() + " numbers belong to no mod that was found", byMod);
        }

        Set<Integer> dropped = new java.util.HashSet<>();
        owned.forEach((mod, ids) -> {
            if (!clientHas.contains(mod)) {
                dropped.addAll(ids);
            }
        });
        return dropped;
    }

    private static final Set<Class<?>> UNSURE_REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Says once, for a class, why its fields could not be split between the mods that added them. */
    private static @org.jspecify.annotations.Nullable Set<Integer> unsure(Class<?> type, String why, Map<String, List<Integer>> found) {
        if (UNSURE_REPORTED.add(type)) {
            PolymerPatcher.LOGGER.warn("Could not tell which of {}'s fields belong to which mod ({}; found {}), so a player with some "
                + "of the mods adding to it and not others may be disconnected", type.getSimpleName(), why, found);
        }
        return null;
    }

    private static int[] buildIndexMap(Layout layout) {
        // Superclass first, because that is the order the numbers were handed out in
        Deque<Class<?>> chain = new ArrayDeque<>();
        for (Class<?> type = layout.entityClass(); type != null && Entity.class.isAssignableFrom(type); type = type.getSuperclass()) {
            chain.addFirst(type);
        }

        Map<Integer, Integer> mapped = new HashMap<>();
        Map<Integer, String> expects = new HashMap<>();
        int highest = -1;
        int clientNextId = 0;
        int serverNextId = 0;
        boolean shifted = false;

        for (Class<?> type : chain) {
            int vanillaCount = vanillaFieldCount(type);
            List<Integer> ids = declaredIds(type);

            // What the server actually handed this class, mods included - asked of the game's own
            // allocator rather than counted off the class, because a mod that keeps its accessors in a
            // holder of its own declares nothing on the class it adds them to
            int allocated = allocatedCount(type);
            Class<?> superClass = type.getSuperclass();
            // Entity's own superclass is Object, which the allocator has never been asked about and
            // answers -1 for. That is a count of none, not a failure to read one - and taking it for a
            // failure threw away the allocator's answer for Entity itself, falling back to counting the
            // fields declared on the class. Which is the one count that cannot see a holder class: the
            // four Alex's Caves puts on Entity went unnoticed, every class below it was placed four
            // numbers too low, and a player's health landed where the client keeps its stinger count.
            int inherited = superClass != null && Entity.class.isAssignableFrom(superClass)
                ? allocatedCount(superClass)
                : 0;

            int serverCount;
            if (allocated >= 0 && inherited >= 0 && allocated >= inherited) {
                serverCount = allocated - inherited;
                // Where the game itself started this class, rather than counting on from the class
                // before it. One class read wrongly used to shift every class after it; anchoring each
                // to the allocator keeps a bad read from spreading
                serverNextId = inherited;
            } else {
                serverCount = ids.size();
            }

            if (vanillaCount == Integer.MAX_VALUE) {
                vanillaCount = serverCount;
            }

            // Whether this client has every mod that adds to this class. If it does, the fields those
            // mods added are ones it can read and must be kept; if it does not, they are numbers it
            // has nowhere to put
            Set<String> adders = TrackedDataMods.modsAddingTo(type);
            boolean keepExtras = !adders.isEmpty() && layout.clientHas().containsAll(adders);
            int keep = keepExtras ? serverCount : Math.min(vanillaCount, serverCount);

            // A client with some of these mods and not others. Keeping every mod's fields or none of them
            // was wrong both ways: Yazz's Dungeons and Alex's Caves both add to Entity, and a player with only
            // Alex's Caves had its four taken away along with the one they lacked - every field after them
            // landed four numbers early, and a squid's flags arrived where the client keeps a count. Each
            // mod's own numbers, read back out of the fields it keeps them in, are kept or dropped by
            // themselves instead
            Set<Integer> dropped = null;
            if (!keepExtras && !adders.isEmpty() && !java.util.Collections.disjoint(adders, layout.clientHas())) {
                dropped = droppedFor(type, layout.clientHas(), vanillaCount, serverNextId, serverCount);
                if (dropped != null) {
                    keep = serverCount - dropped.size();
                }
            }

            // TEMPORARY DIAGNOSTIC - remove once the numbering is understood
            PolymerPatcher.LOGGER.debug("{} of {}: vanilla={} allocated={} inherited={} serverCount={} keep={} adders={} declaredIds={} serverIds={}..{} -> clientIds={}..{}",
                type.getSimpleName(), layout.entityClass().getSimpleName(),
                vanillaCount, allocated, inherited, serverCount, keep, adders, ids,
                serverNextId, serverNextId + serverCount - 1,
                clientNextId, clientNextId + keep - 1);

            // What the client keeps at each of these, so a value of the wrong kind can be noticed
            // before it is sent rather than after it has ended the connection
            List<String> serializers = vanillaSerializerNames(type);

            int kept = 0;
            for (int i = 0; i < serverCount; i++) {
                int serverId = serverNextId + i;
                highest = Math.max(highest, serverId);
                if (dropped != null ? dropped.contains(serverId) : i >= keep) {
                    // A field this client has nowhere to put; anything carrying its number is dropped
                    shifted = true;
                    continue;
                }
                int clientId = clientNextId + kept;
                mapped.put(serverId, clientId);
                if (kept < serializers.size() && !serializers.get(kept).isEmpty()) {
                    expects.put(clientId, serializers.get(kept));
                }
                kept++;
                shifted |= serverId != clientId;
            }

            serverNextId += serverCount;
            clientNextId += keep;
        }

        if (shifted) {
            PolymerPatcher.LOGGER.info("{} carries tracked data numbered differently from a client with {}; its updates will be renumbered",
                layout.entityClass().getSimpleName(),
                layout.clientHas().isEmpty() ? "none of the mods that shift them" : layout.clientHas());
        }

        int[] map = new int[highest + 1];
        java.util.Arrays.fill(map, -1);
        mapped.forEach((serverId, vanillaId) -> map[serverId] = vanillaId);

        String[] expected = new String[clientNextId];
        expects.forEach((clientId, named) -> {
            if (clientId < expected.length) {
                expected[clientId] = named;
            }
        });
        EXPECTED_BY_LAYOUT.put(layout, expected);

        return map;
    }


    /**
     * The tracked data numbers a class hands out, in the order it hands them out, which for fields
     * assigned in a static initialiser is the order they are declared in. A mod's additions are merged
     * in after the game's own, so they come last and are numbered last.
     */
    private static List<Integer> declaredIds(Class<?> type) {
        List<Integer> ids = new ArrayList<>();

        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;
            if (!EntityDataAccessor.class.isAssignableFrom(field.getType())) continue;

            try {
                if (field.trySetAccessible() && field.get(null) instanceof EntityDataAccessor<?> accessor) {
                    ids.add(accessor.id());
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                // A field that will not be read is one number this class cannot account for; the
                // sort below still keeps the rest in the order they were handed out
            }
        }

        ids.sort(null);
        return ids;
    }

    /**
     * How many tracked data fields the game's own copy of a class declares, counted out of the jar.
     * Zero for anything the vanilla game has never heard of, which is the right answer for a modded
     * entity: none of its fields mean anything to a client without the mod.
     */
    private static final Map<String, List<String>> VANILLA_FIELD_SERIALIZERS = new ConcurrentHashMap<>();

    /**
     * Which serializer each of a vanilla class's fields uses, in the order they are handed out.
     * <p>
     * Read from the same {@code <clinit>} the fields are counted in, because the call that defines one
     * names its serializer right beside it. Knowing that a client keeps a float at a given index is
     * what makes it possible to notice, before sending, that a byte is about to go there.
     */
    private static List<String> vanillaSerializerNames(Class<?> type) {
        return VANILLA_FIELD_SERIALIZERS.computeIfAbsent(type.getName().replace('.', '/'), internalName -> {
            byte[] bytes = ResourceHelper.getVanillaClass(internalName);
            if (bytes == null) {
                return List.of();
            }

            try {
                ClassNode node = new ClassNode();
                new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

                List<String> found = new ArrayList<>();
                for (MethodNode method : node.methods) {
                    if (!method.name.equals("<clinit>")) continue;

                    String named = null;
                    for (var instruction : method.instructions) {
                        if (instruction instanceof org.objectweb.asm.tree.FieldInsnNode field
                            && field.getOpcode() == Opcodes.GETSTATIC
                            && field.owner.equals(SERIALIZERS)) {
                            named = field.name;
                        } else if (instruction instanceof MethodInsnNode call
                            && call.getOpcode() == Opcodes.INVOKESTATIC
                            && call.owner.equals(SYNCHED_ENTITY_DATA)
                            && call.name.equals("defineId")) {
                            // Empty where the serializer came from somewhere this cannot read, which
                            // means "no opinion" rather than "wrong"
                            found.add(named == null ? "" : named);
                            named = null;
                        }
                    }
                }
                return List.copyOf(found);
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.debug("Could not read the serializers {} uses", internalName, e);
                return List.of();
            }
        });
    }

    /** Every serializer the game has, by the name the class files call it. */
    private static final Map<EntityDataSerializer<?>, String> SERIALIZER_NAMES = nameSerializers();

    private static Map<EntityDataSerializer<?>, String> nameSerializers() {
        Map<EntityDataSerializer<?>, String> names = new java.util.IdentityHashMap<>();
        try {
            for (Field field : EntityDataSerializers.class.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) continue;
                if (!EntityDataSerializer.class.isAssignableFrom(field.getType())) continue;
                if (!field.trySetAccessible()) continue;
                if (field.get(null) instanceof EntityDataSerializer<?> serializer) {
                    names.put(serializer, field.getName());
                }
            }
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.warn("Could not name the game's tracked data serializers; values will not be type-checked", e);
        }
        return names;
    }

    /** Resolves only the serializer fields present in Minecraft's own, unmixed class. */
    private static Set<EntityDataSerializer<?>> findVanillaSerializers() {
        byte[] bytes = ResourceHelper.getVanillaClass(SERIALIZERS);
        if (bytes == null) {
            PolymerPatcher.LOGGER.warn("Could not read the game's tracked data serializer class; falling back to its numeric serializer boundary");
            return Set.of();
        }

        try {
            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

            Set<EntityDataSerializer<?>> serializers = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            int declared = 0;
            String descriptor = EntityDataSerializer.class.descriptorString();
            for (var vanillaField : node.fields) {
                if (!descriptor.equals(vanillaField.desc)) continue;
                declared++;

                Field liveField = EntityDataSerializers.class.getDeclaredField(vanillaField.name);
                if (!Modifier.isStatic(liveField.getModifiers()) || !liveField.trySetAccessible()) {
                    throw new IllegalStateException("Cannot read vanilla serializer field " + vanillaField.name);
                }
                if (!(liveField.get(null) instanceof EntityDataSerializer<?> serializer)) {
                    throw new IllegalStateException("Vanilla serializer field " + vanillaField.name + " has the wrong value");
                }
                serializers.add(serializer);
            }

            if (declared == 0 || serializers.size() != declared) {
                throw new IllegalStateException("Found " + serializers.size() + " of " + declared + " vanilla serializers");
            }

            return java.util.Collections.unmodifiableSet(serializers);
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.warn("Could not identify the game's own tracked data serializers; falling back to its numeric serializer boundary", e);
            return Set.of();
        }
    }

    private static int vanillaFieldCount(Class<?> type) {
        return VANILLA_FIELD_COUNTS.computeIfAbsent(type.getName().replace('.', '/'), internalName -> {
            byte[] bytes = ResourceHelper.getVanillaClass(internalName);
            if (bytes == null) {
                return 0;
            }

            try {
                ClassNode node = new ClassNode();
                new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

                int count = 0;
                for (MethodNode method : node.methods) {
                    if (!method.name.equals("<clinit>")) continue;

                    for (var instruction : method.instructions) {
                        if (instruction instanceof MethodInsnNode call
                            && call.getOpcode() == Opcodes.INVOKESTATIC
                            && call.owner.equals(SYNCHED_ENTITY_DATA)
                            && call.name.equals("defineId")) {
                            count++;
                        }
                    }
                }
                return count;
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.warn("Could not read {} out of the vanilla jar; its tracked data will be left alone", internalName, e);
                // Read as "everything this class declares is the game's own", which leaves the entity
                // exactly as it was before this check existed rather than stripping it bare
                return Integer.MAX_VALUE;
            }
        });
    }

    /**
     * Says what was dropped, once per kind, because a value going missing is quiet where a disconnect
     * is not.
     */
    private static void report(SynchedEntityData.DataValue<?> value, @Nullable Entity entity, String reason) {
        String type = entity != null ? entity.getClass().getName() : "an unknown entity";
        if (!REPORTED.add(type + "#" + value.id())) {
            return;
        }

        PolymerPatcher.LOGGER.warn("Dropped tracked data index {} on {}: {}. The value will not reach players; everything else about the entity still will.",
            value.id(), type, reason);
    }

    private static int findSerializerLimit() {
        int highest = -1;
        try {
            for (EntityDataSerializer<?> serializer : VANILLA_SERIALIZER_TYPES) {
                highest = Math.max(highest, EntityDataSerializers.getSerializedId(serializer));
            }
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.warn("Could not read the game's own tracked data serializers; none will be filtered out", e);
            return Integer.MAX_VALUE;
        }

        // Reading nothing means the assumption behind this whole check is wrong, and dropping every
        // entry on that basis would be far worse than dropping none
        if (highest < 0) {
            PolymerPatcher.LOGGER.warn("Found no untouched tracked data serializers in the game class; none will be filtered out by number");
            return Integer.MAX_VALUE;
        }
        return highest + 1;
    }
}
