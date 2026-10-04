package me.drex.polymerpatcher.registry;

import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.other.*;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import eu.pb4.polymer.rsm.api.RegistrySyncUtils;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.block.PolymerBlockHelper;
import eu.pb4.polymer.common.api.PolymerCommonUtils;
import me.drex.polymerpatcher.entity.AutomaticPolymerEntity;
import me.drex.polymerpatcher.resources.ResourceHelper;
import me.drex.polymerpatcher.util.NativeClients;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.entity.EntityTypes;
import me.drex.polymerpatcher.item.PolyBaseItem;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

public class RegistryPatcher {
    private static final DustColorTransitionOptions AZURE_MAGNETIC =
        new DustColorTransitionOptions(0x36D9FF, 0x5265FF, 0.82F);
    private static final DustColorTransitionOptions SCARLET_MAGNETIC =
        new DustColorTransitionOptions(0xFF3159, 0xFF8A35, 0.82F);
    private static final DustColorTransitionOptions MAGNETIC_AMBIENT =
        new DustColorTransitionOptions(0xB744FF, 0x55D9FF, 0.62F);

    public static void patchRegistries() {
        PolymerPatcher.LOGGER.info("Patching registry entries...");

        // Before the sweep below, because it decides how every sound found in it is sent
        me.drex.polymerpatcher.sound.SoundPatching.handleBlockSoundsOnTheServer();

        patchRegistry(BuiltInRegistries.ENTITY_TYPE, RegistryPatcher::patchEntityType);
        patchRegistry(BuiltInRegistries.ITEM, (identifier, item) -> PolymerItem.registerOverlay(item, new PolyBaseItem(item)));
        patchRegistry(BuiltInRegistries.BLOCK,
            Comparator.comparing(PolymerBlockHelper::getPriority),
            PolymerBlockHelper::registerPolymerBlock
        );
        patchRegistry(BuiltInRegistries.PARTICLE_TYPE, RegistryPatcher::patchParticleType);

        patchRegistry(BuiltInRegistries.MOB_EFFECT, RegistryPatcher::patchMobEffect);
        patchRegistry(BuiltInRegistries.MENU, RegistryPatcher::patchMenuType);

        // These registries need more than a random synced stand-in. In particular, attributes and
        // block entities have dedicated Polymer registration paths, while fluids/potions/game events
        // need values a vanilla client can actually decode.
        patchAttributes();
        patchRegistry(BuiltInRegistries.BLOCK_ENTITY_TYPE,
            (identifier, blockEntityType) -> PolymerBlockUtils.registerBlockEntity(blockEntityType));
        patchRegistry(BuiltInRegistries.FLUID, (identifier, fluid) -> {
            PolymerSyncedObject.setPlainSyncedObject(BuiltInRegistries.FLUID, fluid, new PolymerSyncedObject<>() {
                @Override
                public net.minecraft.world.level.material.Fluid getPolymerReplacement(net.minecraft.world.level.material.Fluid object, PacketContext context) {
                    return Fluids.LAVA;
                }

                @Override
                public boolean canSyncRawToClient(PacketContext context) {
                    // A companion client registered its own copy of this fluid, under this name
                    return me.drex.polymerpatcher.companion.CompanionServer.tagsReady(context, fluid);
                }
            });
            RegistrySyncUtils.setServerEntry(BuiltInRegistries.FLUID, fluid);
        });
        patchRegistry(BuiltInRegistries.GAME_EVENT, (identifier, gameEvent) -> {
            PolymerSyncedObject.setPlainSyncedObject(BuiltInRegistries.GAME_EVENT, gameEvent, (object, context) -> GameEvent.FLAP.value());
            RegistrySyncUtils.setServerEntry(BuiltInRegistries.GAME_EVENT, gameEvent);
        });
        patchRegistry(BuiltInRegistries.POTION, (identifier, potion) -> {
            PolymerSyncedObject.setPlainSyncedObject(BuiltInRegistries.POTION, potion, (object, context) -> Potions.LUCK.value());
            RegistrySyncUtils.setServerEntry(BuiltInRegistries.POTION, potion);
        });
        patchRegistry(BuiltInRegistries.POINT_OF_INTEREST_TYPE,
            (identifier, poiType) -> RegistrySyncUtils.setServerEntry(BuiltInRegistries.POINT_OF_INTEREST_TYPE, poiType));
        patchRegistry(BuiltInRegistries.CUSTOM_STAT,
            (identifier, stat) -> markServerEntry(BuiltInRegistries.CUSTOM_STAT, stat));

        patchRegistry(BuiltInRegistries.DATA_COMPONENT_TYPE, (identifier, dataComponentType) -> PolymerComponent.registerDataComponent(dataComponentType));
        patchRegistry(BuiltInRegistries.ENCHANTMENT_EFFECT_COMPONENT_TYPE, (identifier, dataComponentType) -> PolymerComponent.registerEnchantmentEffectComponent(dataComponentType));

        // Sound events are told to a stranger as whichever vanilla sound they are closest to, because
        // a client can only decode references to sounds it already has. Registry data a mod sends - a
        // biome or dimension's ambient attributes - names those sounds by id, so every reference must
        // resolve to something on the client's side or loading that registry fails and the join is
        // dropped.
        //
        // The overlay must be registered as a PolymerSoundEvent, not as a plain synced object: the
        // packet codec (ByteBufCodecs$30) only translates a modded sound on its way to a client when
        // it finds a PolymerSoundEvent overlay for it. Anything registered any other way falls through
        // to "send the holder as itself", and the client reads the cast's own raw index as a vanilla
        // sound it was never meant to be - the sound jumble.
        patchRegistry(BuiltInRegistries.SOUND_EVENT, (identifier, soundEvent) -> {
            // The stand-in goes on every modded sound, whether or not the pack can play it.
            // Registry data names sounds by id - a biome's ambience, the End's music, a jukebox
            // song's track - and a joining client resolves those against its own registry, where a
            // modded name is not found and the whole registry load fails with it. Leaving it off for
            // sounds the pack carries is what made every Enderscape biome and every modded disc
            // unparseable, and nobody could get in at all
            SoundEvent standIn = closestVanillaSoundEvent(identifier);
            if (standIn != null) {
                PolymerSoundEvent.registerOverlay(soundEvent, standIn);
            }
            // Separately, and only for playing one: a sound packet can carry a name instead of a
            // registry number, and a client reads that name out of the pack it downloaded. Registry
            // data is not sent that way, so the stand-in above still stands where it is needed
            me.drex.polymerpatcher.sound.SoundPatching.sendByNameWherePossible(identifier);
            RegistrySyncUtils.setServerEntry(BuiltInRegistries.SOUND_EVENT, soundEvent);
        });

        BuiltInRegistries.REGISTRY.listElements()
            .forEach(reference -> patchRegistry(reference.value()));

        PolymerPatcher.PATCHED_MODS.forEach(PolymerPatcher::setupModAssets);

        if (!REPORTED_IMPOSTORS.isEmpty()) {
            PolymerPatcher.LOGGER.info("{} entries are registered under the game's own name but a mod added them; all are patched as a mod's (run with debug logging to list them)",
                REPORTED_IMPOSTORS.size());
        }

        me.drex.polymerpatcher.sound.SoundPatching.report();
        me.drex.polymerpatcher.block.AutomaticFactoryBlock.reportMapping();
    }

    private static void patchAttributes() {
        BuiltInRegistries.ATTRIBUTE.listElements().forEach(reference -> {
            Identifier identifier = reference.key().identifier();
            if (isVanillaId(identifier) || RegistrySyncUtils.isServerEntry(BuiltInRegistries.ATTRIBUTE, identifier)) {
                return;
            }

            PolymerPatcher.PATCHED_MODS.add(identifier.getNamespace());
            PolymerEntityUtils.registerAttribute(reference);
        });
    }

    private static void patchParticleType(Identifier identifier, net.minecraft.core.particles.ParticleType<?> particleType) {
        String path = identifier.getPath();
        var replacement = switch (path) {
            case "alluring_magnia", "blinklight_spores", "celestial_spores" -> ParticleTypes.WHITE_ASH;
            case "chorus_pollen", "corrupt_spores" -> ParticleTypes.CRIMSON_SPORE;
            case "drift_jelly_dripping", "rustle_sleeping_bubble" -> ParticleTypes.BUBBLE;
            case "ender_pearl", "mirror_teleport_in", "mirror_teleport_out", "void_poof" -> ParticleTypes.PORTAL;
            case "end_trial_spawner_detection", "end_trial_spawner_exhale" -> ParticleTypes.TRIAL_SPAWNER_DETECTED_PLAYER;
            case "end_vault_connection" -> ParticleTypes.VAULT_CONNECTION;
            case "nebulite_ore" -> ParticleTypes.SCRAPE;
            case "rustle_sleeping_bubble_pop" -> ParticleTypes.BUBBLE_POP;
            case "void_stars", "dripping_void_lachryma", "void_entity", "void_splash" -> ParticleTypes.MYCELIUM;
            case "void_entity_destruction" -> ParticleTypes.SMOKE;
            case "dash_jump_shockwave" -> ParticleTypes.SONIC_BOOM;
            case "veiled_leaves" -> ParticleTypes.PALE_OAK_LEAVES;
            case "rustle_converting" -> ParticleTypes.HAPPY_VILLAGER;

            // Neverend: preserve the expanding sonar/readout distinction with vanilla-safe effects.
            case "sonar" -> ParticleTypes.SONIC_BOOM;
            case "sonar_eye" -> ParticleTypes.ELECTRIC_SPARK;
            case "orb" -> ParticleTypes.GLOW;

            // Sculk Horde. Crust is a speck of near-black sculk drifting for ten seconds, which vanilla's
            // ash is; a burrowed burst is a scatter of teal bits; the ancient dialect is a white glyph
            // hanging in place, which is what the enchanting table's glyphs are at zero speed
            case "sculk_crust_particle" -> ParticleTypes.ASH;
            case "burrowed_burst_particle" -> new net.minecraft.core.particles.DustParticleOptions(0x14B8B4, 1.0F);
            case "ancient_dialect_particle" -> ParticleTypes.ENCHANT;

            // Alex's Caves, whose particles are many and were all being sent as the same one. The
            // magnetic caves are the clearest loss: the red and blue streaks running between magnetic
            // blocks are the whole look of the place, and azure and scarlet arriving as the same
            // particle flattened it entirely. Transition dust keeps the two polarities distinct and
            // preserves the source velocity as a narrow streak instead of a large fire sprite.
            case "azure_magnetic_flow", "azure_magnetic_orbit", "azure_shield_lightning" -> AZURE_MAGNETIC;
            case "scarlet_magnetic_flow", "scarlet_magnetic_orbit", "scarlet_shield_lightning" -> SCARLET_MAGNETIC;
            case "magnetic_caves_ambient" -> MAGNETIC_AMBIENT;
            case "magnet_lightning", "tesla_bulb_lightning", "quarry_border_lightning",
                 "tremorzilla_lightning", "tremorzilla_retro_lightning",
                 "tremorzilla_tectonic_lightning" -> ParticleTypes.ELECTRIC_SPARK;

            case "acid_bubble", "purple_soda_bubble", "purple_soda_bubble_emitter",
                 "purple_soda_fizz" -> ParticleTypes.BUBBLE;
            case "acid_drop", "caramel_drop", "ice_cream_drip" -> ParticleTypes.FALLING_WATER;
            case "big_splash", "big_splash_effect", "ice_cream_splash", "radgill_splash",
                 "water_foam", "water_tremor" -> ParticleTypes.SPLASH;

            case "amber_explosion", "blue_raygun_explosion", "raygun_explosion", "raygun_blast",
                 "conversion_crucible_explosion", "frostmint_explosion", "mine_explosion",
                 "purple_witch_explosion", "totem_explosion", "underzealot_explosion",
                 "tremorzilla_explosion", "tremorzilla_retro_explosion",
                 "tremorzilla_tectonic_explosion", "mushroom_cloud_explosion" -> ParticleTypes.EXPLOSION;
            case "mushroom_cloud", "mushroom_cloud_smoke", "tremorzilla_steam" -> ParticleTypes.LARGE_SMOKE;
            case "black_vent_smoke", "green_vent_smoke", "red_vent_smoke",
                 "white_vent_smoke" -> ParticleTypes.CAMPFIRE_COSY_SMOKE;

            case "fallout", "luxtructosaurus_ash", "moth_dust", "sugar_flake",
                 "galena_debris" -> ParticleTypes.WHITE_ASH;
            case "big_block_dust", "colored_dust", "small_colored_dust" -> ParticleTypes.ASH;

            case "deep_one_magic", "purple_witch_magic", "underzealot_magic",
                 "witch_cookie" -> ParticleTypes.WITCH;
            case "proton", "tremorzilla_proton", "tremorzilla_retro_proton",
                 "tremorzilla_tectonic_proton", "rainbow", "player_rainbow", "sundrop" -> ParticleTypes.GLOW;
            case "happiness", "jelly_bean_eat" -> ParticleTypes.HEART;
            case "sleep" -> ParticleTypes.CLOUD;
            case "stun_star", "candicorn_charge" -> ParticleTypes.CRIT;
            case "forsaken_sonar", "forsaken_sonar_large", "nuclear_siren_sonar",
                 "watcher_appearance" -> ParticleTypes.SONIC_BOOM;
            case "tephra", "tephra_flame", "tephra_small" -> ParticleTypes.FLAME;
            case "hazmat_breathe", "blue_hazmat_breathe" -> ParticleTypes.CLOUD;
            case "void_being_cloud", "void_being_eye", "void_being_tendril" -> ParticleTypes.PORTAL;
            case "amber_monolith", "dinosaur_transformation_amber",
                 "dinosaur_transformation_tectonic" -> ParticleTypes.END_ROD;
            case "bio_pop", "ferrouslime", "fly", "gammaroach", "gobthumper", "tube_worm",
                 "falling_guano", "forsaken_spit", "luxtructosaurus_spit" -> ParticleTypes.MYCELIUM;
            default -> genericParticleForName(path);
        };
        PolymerParticleType.setOverlay(particleType, (particleOptions, packetContext) -> replacement);
    }

    /** Best-effort semantics for particles from any mod, based on the descriptive registry path. */
    private static net.minecraft.core.particles.ParticleOptions genericParticleForName(String path) {
        if (path.contains("bubble_pop")) return ParticleTypes.BUBBLE_POP;
        if (path.contains("bubble")) return ParticleTypes.BUBBLE;
        if (path.contains("soul") && path.contains("flame")) return ParticleTypes.SOUL_FIRE_FLAME;
        if (path.contains("flame") || path.contains("fire")) return ParticleTypes.FLAME;
        if (path.contains("smoke")) return ParticleTypes.SMOKE;
        if (path.contains("portal") || path.contains("teleport") || path.contains("ender")) return ParticleTypes.PORTAL;
        if (path.contains("splash")) return ParticleTypes.SPLASH;
        if (path.contains("drip") || path.contains("droplet") || path.contains("falling_liquid")) return ParticleTypes.DRIPPING_WATER;
        if (path.contains("leaf") || path.contains("leaves")) return ParticleTypes.PALE_OAK_LEAVES;
        if (path.contains("spore") || path.contains("ash") || path.contains("pollen")) return ParticleTypes.WHITE_ASH;
        if (path.contains("dust") || path.contains("spark") || path.contains("electric")) return ParticleTypes.ELECTRIC_SPARK;
        if (path.contains("heart")) return ParticleTypes.HEART;
        if (path.contains("note") || path.contains("music")) return ParticleTypes.NOTE;
        if (path.contains("explosion")) return ParticleTypes.EXPLOSION;
        if (path.contains("poof") || path.contains("cloud")) return ParticleTypes.POOF;
        if (path.contains("crit")) return ParticleTypes.CRIT;
        if (path.contains("snow") || path.contains("frost")) return ParticleTypes.SNOWFLAKE;
        if (path.contains("sculk")) return ParticleTypes.SCULK_SOUL;
        // A neutral glowing mote is less misleading than the previous angry-villager fallback.
        return ParticleTypes.END_ROD;
    }


    /**
     * Shows a modded effect as whichever of the game's own effects it is most like.
     * <p>
     * A client cannot be given an effect it does not have. The list is fixed in the game itself rather
     * than sent by the server, so a modded effect has to arrive as one of the twenty-odd the player
     * already owns - there is no arrangement of resource pack or packet that adds a twenty-ninth.
     * <p>
     * What was happening instead is that every modded effect was being shown as the same one, whichever
     * the game happened to list first, because the sweep at the end of {@link #patchRegistries} maps
     * anything it has no better idea about to {@code registry.getAny()}. Two dozen different effects all
     * appeared as one, with its icon and its colour.
     * <p>
     * So each is matched to the closest thing instead: something harmful is shown as something harmful,
     * and among those the one whose colour is nearest. The name still reads as the effect it was shown
     * as, which cannot be helped - but a harmful green effect now looks like poison rather than like
     * whatever happened to be first.
     */
    /**
     * Says what a client should be told a modded screen is.
     * <p>
     * Nothing was saying anything: the menu registry was the one this never patched, so a modded screen
     * went out as a number no registry a client had been given could explain. What a client makes of
     * that is a container of whatever shape it can manage - the single row of slots that scrambled an
     * inventory the moment a spelunkery table was opened.
     * <p>
     * A stand-in cannot rescue somebody who lacks the mod: a screen is drawn by the mod's own client
     * code and there is nothing to dress a vanilla menu up as. Those are turned away before the screen
     * opens at all - see {@code ModdedMenus}. But somebody who <em>has</em> the mod has the code, and
     * was being handed the same nothing as everybody else purely because this registry was never asked.
     * Told the truth, their client draws the real thing.
     */
    private static void patchMenuType(Identifier identifier, MenuType<?> menuType) {
        String namespace = identifier.getNamespace();
        PolymerSyncedObject.setSyncedObject(BuiltInRegistries.MENU, menuType, new PolymerSyncedObject<MenuType<?>>() {
            @Override
            public MenuType<?> getPolymerReplacement(MenuType<?> original, PacketContext context) {
                // Anything at all for a client that cannot draw the real one; it never gets opened
                return isNative(context, namespace) ? original : MenuType.GENERIC_9x3;
            }

            @Override
            public boolean canSyncRawToClient(PacketContext context) {
                return isNative(context, namespace);
            }
        });
        RegistrySyncUtils.setServerEntry(BuiltInRegistries.MENU, menuType);
    }

    private static void patchMobEffect(Identifier identifier, MobEffect effect) {
        MobEffect standIn = closestVanillaEffect(effect);
        if (standIn == null) {
            return;
        }

        PolymerSyncedObject.setPlainSyncedObject(BuiltInRegistries.MOB_EFFECT, effect, (object, context) -> standIn);
        RegistrySyncUtils.setServerEntry(BuiltInRegistries.MOB_EFFECT, effect);
    }

    @Nullable
    private static MobEffect closestVanillaEffect(MobEffect effect) {
        MobEffect best = null;
        MobEffect bestOfAnyKind = null;
        long bestDistance = Long.MAX_VALUE;
        long bestAnyDistance = Long.MAX_VALUE;

        for (Holder.Reference<MobEffect> reference : BuiltInRegistries.MOB_EFFECT.listElements().toList()) {
            if (!isVanillaId(reference.key().identifier())) {
                continue;
            }

            MobEffect candidate = reference.value();
            long distance = colourDistance(effect.getColor(), candidate.getColor());

            if (distance < bestAnyDistance) {
                bestAnyDistance = distance;
                bestOfAnyKind = candidate;
            }

            // Helpful shown as helpful, harmful as harmful - getting that the wrong way round reads as
            // a lie about what the effect is doing to you
            if (candidate.getCategory() == effect.getCategory() && distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }

        return best != null ? best : bestOfAnyKind;
    }

    /**
     * The modded sound a stranger should be told a sound event is, when the game's own sounds are all
     * that module can name.
     * <p>
     * The registry is no place for a subtle decision: a client has exactly the sounds its own jar
     * ships, so every modded sound has to arrive as one of those, and which one is what a sound
     * packet's raw index ends up meaning. What was happening instead is that every modded sound was
     * replaced by the same one - whichever the game happened to list first - so a cave's drip, a
     * legion of mob calls and a potion's glug all played back as the same sound.
     * <p>
     * Every modded sound is therefore given a vanilla sound - something is returned for everything,
     * because leaving one without a stand-in sends its own raw index to the client, where it reads as
     * whichever vanilla sound happens to live at that number. A modded sound that shares words with a
     * vanilla one goes to the best of those; among equal contenders, and everywhere else, the choice
     * is spread across the candidates by the sound's own id, so two different sounds never pick the
     * same stand-in merely because neither has a word in common with it. An ambient sound stays among
     * ambient sounds, a mob's call among entity sounds, a track among music - imperfect names over a
     * single flat list.
     */
    private static SoundEvent closestVanillaSoundEvent(Identifier identifier) {
        String need = identifier.getPath();
        String kind = leadingSoundKind(need);

        int bestScoreOverall = 0;
        List<SoundEvent> bestOverall = new ArrayList<>();
        int bestScoreSameKind = 0;
        List<SoundEvent> bestSameKind = new ArrayList<>();
        List<SoundEvent> sameKind = new ArrayList<>();
        List<SoundEvent> all = new ArrayList<>();

        for (Holder.Reference<SoundEvent> reference : BuiltInRegistries.SOUND_EVENT.listElements().toList()) {
            // Only sounds the game really has: a mod's sound under the game's name is no stand-in, as a
            // vanilla client has nothing at its number either
            if (!isVanillaId(reference.key().identifier()) || !ResourceHelper.isGamesOwnSoundEvent(reference.key().identifier())) {
                continue;
            }
            SoundEvent value = reference.value();
            String have = reference.key().identifier().getPath();
            all.add(value);

            int score = soundPathScore(need, have);
            if (score > bestScoreOverall) {
                bestScoreOverall = score;
                bestOverall.clear();
            }
            if (score == bestScoreOverall) {
                bestOverall.add(value);
            }

            if (!kind.equals(leadingSoundKind(have))) {
                continue;
            }
            sameKind.add(value);
            if (score > bestScoreSameKind) {
                bestScoreSameKind = score;
                bestSameKind.clear();
            }
            if (score == bestScoreSameKind) {
                bestSameKind.add(value);
            }
        }

        List<SoundEvent> pool;
        if (bestScoreSameKind > 0) {
            pool = bestSameKind;
        } else if (bestScoreOverall > 0) {
            pool = bestOverall;
        } else if (!sameKind.isEmpty()) {
            pool = sameKind;
        } else {
            pool = all;
        }

        return pool.get(Math.floorMod(stableHash(identifier), pool.size()));
    }

    /**
     * The opening word of a sound's name - the {@code ambient} in {@code ambient.cave.loop}, the
     * {@code entity} in {@code entity.crow.caw}. It is the best hint a name gives about the <i>sort</i>
     * of sound it is, which is why the stand-in search keeps it apart.
     */
    private static String leadingSoundKind(String path) {
        int dot = path.indexOf('.');
        return dot < 0 ? path : path.substring(0, dot);
    }

    /**
     * A stable spread inside a candidate pool: the same sound always picks the same candidate, and two
     * differently-named sounds pick different ones. Deterministic across restarts and mod sets, so a
     * sound's stand-in never depends on whatever was registered after it.
     */
    private static int stableHash(Identifier id) {
        int hash = 1;
        String text = id.toString();
        for (int i = 0; i < text.length(); i++) {
            hash = hash * 31 + text.charAt(i);
        }
        return hash;
    }

    /**
     * How much one sound's name reads like another's. A shared word scores its full weight - a
     * category word (ambient, entity, block, ...) less than a real one - and a shared stem counts a
     * little, so {@code entity.crow.caw} reaches {@code entity.parrot.imitate.crow} rather than the
     * head of the list.
     */
    private static int soundPathScore(String need, String have) {
        String[] haveWords = have.split("[._]");
        int score = 0;
        for (String want : need.split("[._]")) {
            for (String haveWord : haveWords) {
                if (haveWord.equals(want)) {
                    score += SOUND_KIND_WORDS.contains(want) ? 2 : 4;
                } else if (Math.min(want.length(), haveWord.length()) >= 4
                    && (want.startsWith(haveWord) || haveWord.startsWith(want))) {
                    score += 1;
                }
            }
        }
        return score;
    }

    private static long colourDistance(int one, int two) {
        long red = ((one >> 16) & 0xFF) - ((two >> 16) & 0xFF);
        long green = ((one >> 8) & 0xFF) - ((two >> 8) & 0xFF);
        long blue = (one & 0xFF) - (two & 0xFF);
        return red * red + green * green + blue * blue;
    }

    private static <T> void patchRegistry(Registry<T> registry) {
        if (registry.key() == BuiltInRegistries.SOUND_EVENT.key()) {
            // Handled on its own so each modded sound maps to the vanilla one it is most like,
            // rather than the registry's first entry
            return;
        }
        Optional<Holder.Reference<T>> defaultElement = registry.getAny();
        patchRegistry(registry, (identifier, t) -> {
            defaultElement.ifPresent(ref -> PolymerSyncedObject.setPlainSyncedObject(registry, t, (object, context) -> ref.value()));
            RegistrySyncUtils.setServerEntry(registry, t);
        });
    }

    private static <T> void markServerEntry(Registry<T> registry, T value) {
        RegistrySyncUtils.setServerEntry(registry, value);
    }

    private static <T> void patchRegistry(Registry<T> registry, BiConsumer<Identifier, T> consumer) {
        patchRegistry(registry, Comparator.comparing(ref -> 0), consumer);
    }

    /**
     * Each entry is patched on its own, so one that will not patch costs only itself.
     * <p>
     * This used to run as a single pass with nothing catching anything. An entry that threw ended the
     * pass where it stood, and <b>everything after it was left unpatched</b> - which for blocks is not a
     * cosmetic loss: an unpatched block has no stand-in, so its own state number goes to the client as
     * it is, and a client with no such block throws the packet out and the connection with it. The
     * ordering is by weight, so which entries were lost depended on where in the list the bad one fell,
     * and nothing said that it had happened.
     * <p>
     * Now the entry is named and the sweep carries on. What is lost is that one entry, and the log says
     * which it was.
     */
    private static <T> void patchRegistry(Registry<T> registry, Comparator<Holder.Reference<T>> comparator, BiConsumer<Identifier, T> consumer) {
        List<Identifier> failed = new ArrayList<>();

        registry.listElements().sorted(comparator).forEachOrdered(ref -> {
            Identifier identifier = ref.key().identifier();

            if (isTheGamesOwn(registry, identifier) || RegistrySyncUtils.isServerEntry(registry, identifier)) return;

            // A mod's entry registered under the game's own name is still patched, but its namespace is
            // not recorded as a mod's: "minecraft" is not a mod, and everything downstream that walks
            // this list - the asset sweep, the check for which mods a player has - would be asking
            // nonsense of it
            if (!isVanillaId(identifier)) {
                PolymerPatcher.PATCHED_MODS.add(identifier.getNamespace());
            }

            try {
                consumer.accept(identifier, ref.value());
            } catch (Throwable e) {
                failed.add(identifier);
                PolymerPatcher.LOGGER.error("Could not patch {} in {}; it will reach clients as itself", identifier, registry.key().identifier(), e);
            }
        });

        if (!failed.isEmpty()) {
            PolymerPatcher.LOGGER.error(
                "{} entr(y/ies) in {} could not be patched and have no stand-in: {}. A client without the mod cannot read them.",
                failed.size(), registry.key().identifier(), failed);
        }
    }

    /**
     * Hands the type to Polymer as an overlay, and says what the client should be told it is.
     * <p>
     * The three-argument form is the one that matters. Without it Polymer decides for itself what the
     * registry tells a client this type is, and that decision is made while the player is joining -
     * before anything else here has been asked anything. A player who had the mod was therefore given a
     * registry saying "marker" and then, once their channels were known, sent the real mob's fields.
     * Passing the answer in keeps the registry, the spawn packet and the updates saying the same thing.
     */
    private static void patchEntityType(Identifier identifier, EntityType<?> entityType) {
        String namespace = identifier.getNamespace();
        PolymerEntityUtils.registerOverlay(entityType, new PolymerSyncedObject<>() {
            @Override
            public EntityType<?> getPolymerReplacement(EntityType<?> original, PacketContext context) {
                return isNative(context, namespace) ? original : EntityTypes.ITEM_DISPLAY;
            }

            @Override
            public boolean canSyncRawToClient(PacketContext context) {
                // A player with the mod keeps the real entry, so the number in a spawn packet still
                // means what it says by the time it reaches them
                return isNative(context, namespace);
            }
        }, x -> new AutomaticPolymerEntity<>((Entity) x));
    }

    private static boolean isNative(PacketContext context, String namespace) {
        return NativeClients.has(PolymerCommonUtils.getPlayer(context), namespace);
    }

    public static boolean isVanillaId(Identifier id) {
        return id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE) || id.getNamespace().equals("brigadier");
    }

    /** Entries already reported as a mod's despite their name, so each is said once. */
    private static final Set<Identifier> REPORTED_IMPOSTORS = new HashSet<>();

    /** The words a vanilla sound name opens with that merely tell what kind of sound it is. */
    private static final Set<String> SOUND_KIND_WORDS = Set.of(
        "ambient", "block", "entity", "item", "music", "record", "ui", "weather", "gameplay"
    );

    /**
     * Whether the vanilla game has this block, whatever the block is called.
     * <p>
     * Anything answering yes is safe to show a client that has no mods at all; anything answering no is
     * not, however much its name suggests otherwise.
     */
    public static boolean isVanillaBlock(Identifier id) {
        return isVanillaBlock(id, candidate -> ResourceHelper.hasVanillaAsset(
            candidate.getNamespace(), "blockstates/" + candidate.getPath() + ".json"));
    }

    /** Testable core of {@link #isVanillaBlock(Identifier)} without starting Fabric's asset locator. */
    static boolean isVanillaBlock(Identifier id, Predicate<Identifier> hasVanillaBlockState) {
        return isVanillaId(id) && hasVanillaBlockState.test(id);
    }

    /**
     * Whether the vanilla game has this entity type, whatever the type is called.
     * <p>
     * Asked of the language file rather than the assets, because an entity type leaves nothing else
     * behind to look for. If that file could not be read the answer falls back to the name, which is the
     * behaviour there was before this check existed.
     */
    public static boolean isVanillaEntityType(Identifier id) {
        if (!isVanillaId(id)) {
            return false;
        }
        return !ResourceHelper.hasVanillaLang() || ResourceHelper.hasVanillaLangKey("entity.minecraft." + id.getPath());
    }

    /**
     * Whether this entry is one the vanilla game has, rather than one a mod added.
     * <p>
     * The name is not the answer. A mod may register its content under {@code minecraft:} - a backport
     * does exactly that, because the whole point is to be the content the next version will ship - and
     * FallDrop Backport adds a hundred-odd blocks and items that way. Every one of them was read as the
     * game's own and passed straight over, so none got a stand-in, and what reached a player without the
     * mod was the raw number of a block or item they had never heard of. Their creative tab showed
     * whatever their own registry happened to hold at those numbers, which on a client with Alex's Mobs
     * is Alex's Mobs; and placing one of them sent a block state number off the end of the client's list
     * and ended the connection.
     * <p>
     * So blocks and items are asked of the game's own jar instead, which is the same question the player
     * is really asking: does a client that has only the game have this. Every awkward vanilla case -
     * air, water, the moving piston, the barrier - ships a blockstate there, and a mod's addition under
     * the same namespace does not.
     * <p>
     * Everything else is still judged by name. Those registries have no asset to look for, and being
     * wrong about them is a wrong icon rather than a lost connection.
     */
    private static boolean isTheGamesOwn(Registry<?> registry, Identifier id) {
        if (!isVanillaId(id)) {
            return false;
        }

        boolean theGamesOwn;
        if (registry.key() == BuiltInRegistries.BLOCK.key()) {
            theGamesOwn = ResourceHelper.hasVanillaAsset(id.getNamespace(), "blockstates/" + id.getPath() + ".json");
        } else if (registry.key() == BuiltInRegistries.ITEM.key()) {
            theGamesOwn = ResourceHelper.hasVanillaAsset(id.getNamespace(), "items/" + id.getPath() + ".json");
        } else if (registry.key() == BuiltInRegistries.ENTITY_TYPE.key()) {
            theGamesOwn = isVanillaEntityType(id);
        } else if (registry.key() == BuiltInRegistries.SOUND_EVENT.key()) {
            theGamesOwn = ResourceHelper.isGamesOwnSoundEvent(id);
        } else {
            theGamesOwn = true;
        }

        if (theGamesOwn) {
            return true;
        }

        // Collected rather than announced. There are over a hundred of these on a server carrying a
        // backport, and a hundred identical lines say no more than one line and a count does
        REPORTED_IMPOSTORS.add(id);
        PolymerPatcher.LOGGER.debug("{} is registered as the game's own but the game has no such entry", id);
        return false;
    }

}
