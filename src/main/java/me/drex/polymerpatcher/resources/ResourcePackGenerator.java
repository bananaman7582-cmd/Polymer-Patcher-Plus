package me.drex.polymerpatcher.resources;


import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.factorytools.api.block.model.generic.BlockStateModelManager;
import eu.pb4.factorytools.api.resourcepack.ModelModifiers;
import eu.pb4.polymer.resourcepack.api.AssetPaths;
import eu.pb4.polymer.resourcepack.api.PackResource;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import eu.pb4.polymer.resourcepack.extras.api.format.atlas.AtlasAsset;
import eu.pb4.polymer.resourcepack.extras.api.format.blockstate.BlockStateAsset;
import eu.pb4.polymer.resourcepack.extras.api.format.item.ItemAsset;
import eu.pb4.polymer.resourcepack.extras.api.format.item.model.BasicItemModel;
import eu.pb4.polymer.resourcepack.extras.api.format.item.tint.MapColorTintSource;
import eu.pb4.polymer.resourcepack.extras.api.format.blockstate.StateModelVariant;
import eu.pb4.polymer.resourcepack.extras.api.format.blockstate.StateMultiPartDefinition;
import eu.pb4.polymer.resourcepack.extras.api.format.model.ModelAsset;
import eu.pb4.polymer.resourcepack.extras.api.format.model.ModelElement;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.config.ConfigManager;
import me.drex.polymerpatcher.entity.AnimatedEntities;
import me.drex.polymerpatcher.resources.ResourceHelper;
import net.minecraft.IdentifierException;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;

import static me.drex.polymerpatcher.PolymerPatcher.id;

public class ResourcePackGenerator {
    private static final Vec3 EXPANSION = new Vec3(0.08, 0.08, 0.08);
    public static final Set<Identifier> SIGNS = new HashSet<>();

    /**
     * Textures that have to be stitched into the block atlas although no block model asks for them.
     * <p>
     * A model this mod writes itself can name a texture that nothing else does, and a texture no
     * blockstate mentions is not stitched - so the model draws with nothing on it. Enderscape's void
     * lachryma is drawn that way, out of the two textures the fluid renderer would have used, and both
     * arrived missing.
     */
    public static final Set<Identifier> EXTRA_SPRITES = new LinkedHashSet<>();
    public static final Set<Identifier> EXPANDABLE_MODELS = new HashSet<>();
    private static final Set<Identifier> EXPANDED_BLOCK_IDS = new HashSet<>();

    public static void setup() {
        PolymerResourcePackUtils.RESOURCE_PACK_AFTER_INITIAL_CREATION_EVENT.register(ResourcePackGenerator::build);
    }

    public static void expandBlockModel(Identifier id) {
        if (EXPANDED_BLOCK_IDS.add(id)) {
            expandBlockModel(id, x -> true);
        }
    }

    public static void expandBlockModel(Identifier id, Predicate<String> variantPredicate) {
        try {
            BlockStateAsset blockStateAsset = ResourceHelper.decodeBlockState(id);

            Map<String, List<StateModelVariant>> variants = blockStateAsset.variants().orElse(Collections.emptyMap());
            variants.entrySet().forEach(entry -> {
                if (variantPredicate.test(entry.getKey())) {
                    entry.getValue().forEach(variant -> expandModel(variant.model()));
                }
            });

            List<StateMultiPartDefinition> multiParts = blockStateAsset.multipart().orElse(Collections.emptyList());
            multiParts.forEach(stateMultiPartDefinition -> stateMultiPartDefinition.apply().forEach(stateModelVariant -> expandModel(stateModelVariant.model())));
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.error("Failed to read blockstate {}: {}", id, e);
        }
    }

    private static void expandModel(Identifier id) {
        try {
            EXPANDABLE_MODELS.add(id.withSuffix(".json"));
            ModelAsset modelAsset = ResourceHelper.decodeModel(id);
            modelAsset.parent().ifPresent(ResourcePackGenerator::expandModel);
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.error("Failed to read model {}: {}", id, e);
        }
    }

    private static void build(ResourcePackBuilder builder) {
        PolymerPatcher.LOGGER.info("Generating resource pack for {}...", Arrays.toString(PolymerPatcher.PATCHED_MODS.toArray()));

        // Queued rather than done here, because the blockstate files it repairs are written by Polymer
        // later than this - a pre-finish task is the one hook that runs after everything else has had
        // its say and before the pack is sealed
        builder.addPreFinishTask(CarrierBlockStates::repair);
        // After the repair, which writes its keys from this server's blocks exactly as Polymer does
        builder.addPreFinishTask(ClientBlockStateKeys::sanitize);
        var atlas = AtlasAsset.builder();
        me.drex.polymerpatcher.entity.render.RenderCaptureRules.generateAssets(builder);
        builder.forEachResource((path, packResource) -> {
            String[] parts = path.split("/", 4);
            if (parts.length < 4) return;
            try {
                Identifier id = Identifier.fromNamespaceAndPath(parts[1], parts[3]);
                if (!parts[0].equals("assets") || !parts[2].equals("models")) return;
                if (!EXPANDABLE_MODELS.contains(id)) return;
                var asset = ModelAsset.fromJson(new String(packResource.readAllBytes(), StandardCharsets.UTF_8));
                expandModelAsset(asset, path, builder);
            } catch (IdentifierException ignored) {
            }
        });

        for (var model : AnimatedEntities.POLY_MODELS) {
            model.generateAssets(builder::addData, atlas);
        }

        for (var model : AnimatedEntities.CITADEL_MODELS) {
            model.generateAssets(builder::addData, atlas);
        }

        for (var model : AnimatedEntities.GECKOLIB_MODELS) {
            model.generateAssets(builder::addData, atlas);
        }

        for (var model : me.drex.polymerpatcher.entity.armor.ArmorModels.ASSETS) {
            model.generateAssets(builder::addData, atlas);
        }

        me.drex.polymerpatcher.entity.FlatEntityModels.generateAssets(builder::addData, atlas);

        // Every namespace that asked for a turned copy, not only the ones counted as mods.
        //
        // A blockstate that turns its model - "uvlock": true with a y rotation, which is how every set
        // of stairs is written - needs a rotated copy of that model written out for it. This ran over
        // the patched mods, and "minecraft" is deliberately not one of them, so a mod registering its
        // blocks under the game's own name got no copies at all: the one facing that needs no turn
        // rendered, and every other facing pointed at a model nobody had written. That is why FallDrop's
        // stairs appeared for a single direction and were missing textures for the rest.
        // Collected rather than thrown, and said once at the end with a count
        Set<Identifier> missingModels = java.util.Collections.synchronizedSet(new LinkedHashSet<>());
        Set<Identifier> unreadableModels = java.util.Collections.synchronizedSet(new LinkedHashSet<>());

        Set<String> turning = new LinkedHashSet<>(PolymerPatcher.PATCHED_MODS);
        turning.addAll(BlockStateModelManager.UV_LOCKED_MODELS.keySet());

        turning.forEach(modid -> {
            for (var entry : BlockStateModelManager.UV_LOCKED_MODELS.getOrDefault(modid, Collections.emptyMap()).entrySet()) {
                String path = entry.getKey();
                Identifier id = Identifier.fromNamespaceAndPath(modid, path).withSuffix(".json");

                var expand = EXPANDABLE_MODELS.contains(id) ? EXPANSION : Vec3.ZERO;

                for (var v : entry.getValue()) {
                    // Each rotated copy on its own, because one that cannot be written must cost only
                    // itself. This used to throw straight out of the pack builder, and Polymer answers
                    // a build that throws by abandoning the whole pack - so a single malformed model
                    // anywhere, in any mod or any pack merged in here, meant every player joined with
                    // no textures at all rather than one block looking wrong.
                    try {
                        var suffix = "_uvlock_" + v.x() + "_" + v.y();
                        var modelId = v.model().withSuffix(suffix);
                        byte[] data = builder.getData(AssetPaths.model(v.model()) + ".json");
                        if (data == null) {
                            missingModels.add(v.model());
                            continue;
                        }
                        var asset = LenientModels.read(data);

                        if (asset.parent().isPresent()) {
                            var parentId = asset.parent().get();
                            byte[] parentData = builder.getDataOrSource(AssetPaths.model(parentId) + ".json");
                            if (parentData == null) {
                                missingModels.add(parentId);
                                continue;
                            }
                            var parentAsset = LenientModels.read(parentData);
                            builder.addData(AssetPaths.model(PolymerPatcher.MOD_ID, parentId.getPath() + suffix) + ".json",
                                ModelModifiers.expandModelAndRotateUVLocked(parentAsset, expand, v.x(), v.y()));
                            builder.addData(AssetPaths.model(modelId) + ".json",
                                new ModelAsset(Optional.of(Identifier.fromNamespaceAndPath(PolymerPatcher.MOD_ID, parentId.getPath() + suffix)), asset.elements(),
                                    asset.textures(), asset.display(), asset.guiLight(), asset.ambientOcclusion()).toBytes());
                        } else {
                            // A model that draws its own shape rather than inheriting one. Turning it used
                            // to need a parent to turn, so these were passed over entirely and every rotated
                            // copy a block asked for was simply never written - which is a block with faces
                            // pointing at models that do not exist, and those faces are seen straight
                            // through. Crop Critters' liverwort is built this way, and so is anything else
                            // that states its own elements
                            builder.addData(AssetPaths.model(modelId) + ".json",
                                ModelModifiers.expandModelAndRotateUVLocked(asset, expand, v.x(), v.y()));
                        }
                    } catch (Throwable e) {
                        // Named, because the model itself is the only thing that can be fixed and
                        // nothing else in the log says which one it was
                        unreadableModels.add(v.model());
                        PolymerPatcher.LOGGER.warn("Could not write a turned copy of {}; that facing will be untextured", v.model(), e);
                    }
                }
            }
        });

        if (!missingModels.isEmpty()) {
            PolymerPatcher.LOGGER.warn("{} model(s) a blockstate asked to be turned are not in the pack: {}", missingModels.size(), missingModels);
        }
        if (!unreadableModels.isEmpty()) {
            PolymerPatcher.LOGGER.error("{} model(s) could not be read and have no turned copies: {}. The pack is still built; fix or remove these to have those facings textured.",
                unreadableModels.size(), unreadableModels);
        }

        leaveOutBackgroundMusic(builder);
        leaveOutUnreadableText(builder);
        replaceClientOnlyItemModels(builder);
        EquipmentFallbacks.generate(builder);
        // After the mod's assets have been copied, so custom gliders can be translated from their
        // client-renderer-only humanoid layer into the wings layer used by vanilla Elytra rendering.
        GliderFallbacks.generate(builder);
        bridgeModelsRegisteredAsTheGamesOwn(builder);
        repairOrphanedSignTemplates(builder);

        me.drex.polymerpatcher.block.fluid.FluidModels.generate(builder);
        SIGNS.forEach(id -> ModelModifiers.createSignModel(builder, id.getNamespace(), id.getPath(), atlas));
        me.drex.polymerpatcher.block.fluid.FluidModels.stitch(EXTRA_SPRITES);
        EXTRA_SPRITES.forEach(atlas::single);

        builder.addData("assets/minecraft/atlases/blocks.json", atlas.build());
    }

    /**
     * Replaces item definitions whose renderer exists only in a modded client.
     *
     * <p>An item definition can contain code-backed model, property and tint types. Copying that JSON
     * into the server pack does not copy the Java codec which teaches a client what, for example,
     * {@code alexsmobs:icon} means. A client without the mod rejects the whole definition, and the
     * stack then has no model at all in inventories or hands. This is distinct from a missing texture:
     * the client log explicitly says the item definition itself could not be parsed.</p>
     *
     * <p>Definitions containing such a type are reduced to an ordinary model reference. Existing
     * legacy item models are retained where they actually draw geometry; block items use their block
     * model; texture-only special items get a generated flat icon. The last resort is a barrier icon,
     * which is deliberately conspicuous but, importantly, never invisible.</p>
     */
    private static void replaceClientOnlyItemModels(ResourcePackBuilder builder) {
        List<ItemFallback> replacements = new ArrayList<>();
        Set<String> unreadableDefinitions = new LinkedHashSet<>();

        builder.forEachResource((path, resource) -> {
            String[] parts = path.split("/", 4);
            if (parts.length != 4 || !parts[0].equals("assets") || !parts[2].equals("items")
                || !parts[3].endsWith(".json") || !PolymerPatcher.PATCHED_MODS.contains(parts[1])) {
                return;
            }

            String itemPath = parts[3].substring(0, parts[3].length() - ".json".length());
            // Exact compat converters register the definitions they translate semantically. A generic
            // static fallback would be readable but would discard their dynamic state.
            if (ItemModelFallbacks.convertedInPlace(parts[1], itemPath)) {
                return;
            }

            try {
                JsonObject root = JsonParser.parseString(new String(resource.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                JsonElement model = root.get("model");
                if (model != null && ItemModelFallbacks.needsModCode(model)) {
                    // The readable definition is written beside the original and stand-ins point at it.
                    // The original itself must not be republished in the server pack: a stock client
                    // attempts to decode every item definition in an enabled pack, even one no stack
                    // currently uses, and logs an error for every mod-only codec. Omitting it is safe for
                    // native clients too, because their installed mod remains the lower-priority source.
                    unreadableDefinitions.add(path);
                    Identifier standIn = ItemModelFallbacks.standInId(parts[1], itemPath);
                    JsonObject tintBridge = ItemTintFallbacks.rewrite(root, parts[1], itemPath);
                    if (tintBridge != null) {
                        replacements.add(new ItemFallback(ItemModelFallbacks.definitionPath(standIn),
                            standIn, null, null, tintBridge.toString()));
                    } else {
                        replacements.add(resolveItemFallback(builder, ItemModelFallbacks.definitionPath(standIn), parts[1], itemPath, model));
                    }
                    ItemModelFallbacks.record(parts[1], itemPath);
                }
            } catch (Throwable e) {
                // Malformed source JSON is reported by the normal pack pipeline. This pass only owns
                // valid definitions whose codec is unavailable on the receiving client.
                PolymerPatcher.LOGGER.debug("Could not inspect item definition {} for a client-only renderer", path, e);
            }
        });

        for (ItemFallback fallback : replacements) {
            if (fallback.generatedModelPath() != null && fallback.generatedModel() != null) {
                builder.addData(fallback.generatedModelPath(), fallback.generatedModel().getBytes(StandardCharsets.UTF_8));
            }
            if (fallback.definition() != null) {
                builder.addData(fallback.definitionPath(), fallback.definition().getBytes(StandardCharsets.UTF_8));
            } else {
                builder.addData(fallback.definitionPath(),
                    new ItemAsset(new BasicItemModel(fallback.model(), List.of()), ItemAsset.Properties.DEFAULT));
            }
        }

        if (!unreadableDefinitions.isEmpty()) {
            Set<String> omitted = Set.copyOf(unreadableDefinitions);
            builder.addResourceConverter((path, resource) -> omitted.contains(path) ? null : resource);
        }

        if (!replacements.isEmpty()) {
            PolymerPatcher.LOGGER.info("Replaced {} item definition(s) that require client-only model code with vanilla-readable fallbacks; their unreadable originals will not be published",
                replacements.size());
        }
    }

    private static ItemFallback resolveItemFallback(ResourcePackBuilder builder, String definitionPath,
                                                     String namespace, String itemPath) {
        return resolveItemFallback(builder, definitionPath, namespace, itemPath, null);
    }

    private static ItemFallback resolveItemFallback(ResourcePackBuilder builder, String definitionPath,
                                                     String namespace, String itemPath,
                                                     @org.jetbrains.annotations.Nullable JsonElement model) {
        // The mod's own definition first, where all it needed the mod for was the question it asks and
        // not the models it answers with. Those models are ordinary files; see withoutModCode
        JsonElement readable = ItemModelFallbacks.withoutModCode(model);
        if (readable != null && !ItemModelFallbacks.needsModCode(readable)) {
            JsonObject definition = new JsonObject();
            definition.add("model", readable);
            return new ItemFallback(definitionPath, Identifier.fromNamespaceAndPath(namespace, "item/" + itemPath),
                null, null, definition.toString());
        }

        // Then a real shape, where the mod keeps one in its own drawing code. Everything below is a
        // way of coping without one, and the flat square at the end is the worst of them
        Identifier baked = me.drex.polymerpatcher.item.CitadelItemModels.bake(builder, namespace, itemPath);
        if (baked != null) {
            // Where the mod draws the shape only in a hand and a picture everywhere else, the definition
            // does the same, with the shape moved to where the mod's renderer would have put it
            String definition = me.drex.polymerpatcher.item.HeldItemPresentations.definition(
                builder, namespace, itemPath, baked);
            return new ItemFallback(definitionPath, baked, null, null, definition);
        }

        Identifier legacyModel = Identifier.fromNamespaceAndPath(namespace, "item/" + itemPath);
        String legacyPath = "assets/" + namespace + "/models/item/" + itemPath + ".json";
        byte[] legacyBytes = builder.getDataOrSource(legacyPath);
        JsonObject legacy = null;

        if (legacyBytes != null) {
            try {
                legacy = JsonParser.parseString(new String(legacyBytes, StandardCharsets.UTF_8)).getAsJsonObject();
                if (legacy.has("parent") || legacy.has("elements")) {
                    return new ItemFallback(definitionPath, legacyModel, null, null);
                }
            } catch (Throwable ignored) {
            }
        }

        Identifier itemId = Identifier.fromNamespaceAndPath(namespace, itemPath);
        var item = BuiltInRegistries.ITEM.getValue(itemId);
        if (item instanceof BlockItem blockItem) {
            Identifier blockId = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock());
            if (blockId != null) {
                return new ItemFallback(definitionPath, blockId.withPrefix("block/"), null, null);
            }
        }

        String texture = null;
        if (legacy != null && legacy.has("textures") && legacy.get("textures").isJsonObject()) {
            JsonElement particle = legacy.getAsJsonObject("textures").get("particle");
            if (particle != null && particle.isJsonPrimitive()) {
                texture = particle.getAsString();
            }
        }
        if (texture == null && builder.getDataOrSource("assets/" + namespace + "/textures/item/" + itemPath + ".png") != null) {
            texture = namespace + ":item/" + itemPath;
        }

        if (texture != null && !texture.startsWith("#")) {
            Identifier generatedId = PolymerPatcher.id("item_fallback/" + namespace + "/" + itemPath);
            String generatedPath = AssetPaths.model(generatedId) + ".json";
            String generated = "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\""
                + texture + "\"}}";
            return new ItemFallback(definitionPath, generatedId, generatedPath, generated);
        }

        return new ItemFallback(definitionPath, Identifier.withDefaultNamespace("item/barrier"), null, null);
    }

    /**
     * @param definition a whole definition to write instead of a plain reference to {@code model}, where one is needed
     */
    private record ItemFallback(String definitionPath, Identifier model, @org.jetbrains.annotations.Nullable String generatedModelPath,
                                @org.jetbrains.annotations.Nullable String generatedModel,
                                @org.jetbrains.annotations.Nullable String definition) {
        ItemFallback(String definitionPath, Identifier model, @org.jetbrains.annotations.Nullable String generatedModelPath,
                     @org.jetbrains.annotations.Nullable String generatedModel) {
            this(definitionPath, model, generatedModelPath, generatedModel, null);
        }
    }

    /**
     * Keeps background music and long ambience out of the pack.
     * <p>
     * Nobody can join until the pack has been downloaded, and every mod's sounds go into it. On a server
     * carrying Enderscape that came to 138 MB, of which 115 MB was sound and 69 MB was Enderscape's music
     * by itself - so joining meant pulling a hundred-odd megabytes before the world would even load, and
     * hosting the pack from the server itself failed for everyone but the host, because several clients
     * pulling that at once do not finish before the download gives up.
     * <p>
     * Music and ambience are the only sounds big enough to matter and the only ones nothing else depends
     * on: a mob still makes its noise and a block still breaks with its own sound, because those are
     * kilobytes rather than megabytes. What is left out is listed in the config, and turning the setting
     * off puts it all back.
     */
    private static void leaveOutBackgroundMusic(ResourcePackBuilder builder) {
        var resources = ConfigManager.config().resources;
        List<String> folders = resources.excludeMusicFromPack
            ? List.copyOf(resources.excludedSoundFolders)
            : List.of();
        Set<String> interactiveMusic = new HashSet<>();

        // Sounds tables are merged by Polymer immediately before pre-finish tasks. Read that final
        // form, so a disc contributed through an extra pack is protected just like one from a mod.
        // The output itself is sorted, but this makes the protection independent of output order.
        if (resources.includeMusicDiscs && !folders.isEmpty()) {
            builder.addPreFinishTask(finished -> {
                finished.forEachResource((path, resource) -> collectInteractiveMusic(path, resource, interactiveMusic));
                if (!interactiveMusic.isEmpty()) {
                    PolymerPatcher.LOGGER.info("Keeping {} music-disc sound file(s) in the generated pack", interactiveMusic.size());
                }
            });
        }

        builder.addResourceConverter((path, resource) -> {
            if (isSoundsTable(path)) {
                return sanitizeSoundTable(builder, path, resource, folders, interactiveMusic);
            }
            if (!folders.isEmpty() && isBackgroundSound(path, folders)
                && !interactiveMusic.contains(path)) {
                // Null is how this says "do not write it at all"
                return null;
            }
            return resource;
        });

        if (!folders.isEmpty()) {
            PolymerPatcher.LOGGER.info("Leaving {} out of the resource pack to keep it small enough to download; set resources.excludeMusicFromPack to false to include them", folders);
        }
    }

    /** A namespace's one sound event table. */
    private static boolean isSoundsTable(String path) {
        String[] parts = path.split("/");
        return parts.length == 3 && parts[0].equals("assets") && parts[2].equals("sounds.json");
    }

    /** Records sound files belonging to explicitly interactive music events before music is omitted. */
    private static void collectInteractiveMusic(String path, PackResource resource, Set<String> into) {
        if (!isSoundsTable(path)) {
            return;
        }
        try {
            String namespace = path.split("/")[1];
            JsonObject table = JsonParser.parseString(resource.asString()).getAsJsonObject();
            for (var event : table.entrySet()) {
                String name = event.getKey().toLowerCase(Locale.ROOT);
                if (!name.contains("disc") && !name.contains("record") && !name.contains("jukebox")) {
                    continue;
                }
                JsonObject definition = event.getValue().isJsonObject() ? event.getValue().getAsJsonObject() : null;
                if (definition == null || !definition.has("sounds") || !definition.get("sounds").isJsonArray()) {
                    continue;
                }
                for (JsonElement sound : definition.getAsJsonArray("sounds")) {
                    if (isSoundEventReference(sound)) {
                        continue;
                    }
                    String soundPath = soundFilePath(namespace, sound);
                    if (soundPath != null) {
                        into.add(soundPath);
                    }
                }
            }
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Could not inspect {} for interactive music", path, e);
        }
    }

    /**
     * Removes file references which this same pack deliberately removes, plus broken references to
     * files a mod never supplied. Keeping the JSON while dropping its audio is what produced thousands
     * of identical client warnings on every resource reload.
     */
    private static PackResource sanitizeSoundTable(ResourcePackBuilder builder, String path, PackResource resource,
                                                   List<String> excludedFolders, Set<String> interactiveMusic) {
        try {
            String namespace = path.split("/")[1];
            JsonObject table = JsonParser.parseString(resource.asString()).getAsJsonObject().deepCopy();
            List<String> emptyEvents = new ArrayList<>();
            boolean changed = false;

            for (var event : table.entrySet()) {
                if (!event.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject definition = event.getValue().getAsJsonObject();
                if (!definition.has("sounds") || !definition.get("sounds").isJsonArray()) {
                    continue;
                }

                JsonArray sounds = definition.getAsJsonArray("sounds");
                JsonArray kept = new JsonArray();
                for (JsonElement sound : sounds) {
                    String soundPath = soundFilePath(namespace, sound);
                    if (isSoundEventReference(sound) || soundPath == null || soundFileWillExist(builder, soundPath,
                        excludedFolders, interactiveMusic)) {
                        kept.add(sound);
                    } else {
                        changed = true;
                    }
                }

                if (kept.isEmpty() && !sounds.isEmpty()) {
                    emptyEvents.add(event.getKey());
                } else if (kept.size() != sounds.size()) {
                    definition.add("sounds", kept);
                }
            }

            emptyEvents.forEach(table::remove);
            return changed ? PackResource.fromJson(table) : resource;
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Could not sanitize sound table {}", path, e);
            return resource;
        }
    }

    private static boolean soundFileWillExist(ResourcePackBuilder builder, String path,
                                              List<String> excludedFolders, Set<String> interactiveMusic) {
        if (interactiveMusic.contains(path)) {
            return true;
        }
        if (!excludedFolders.isEmpty() && isBackgroundSound(path, excludedFolders)) {
            return false;
        }

        // Vanilla audio lives in the client's asset index rather than in the game jar or this pack.
        // Its absence from the builder therefore says nothing. Other namespaces must supply a file.
        String[] parts = path.split("/", 3);
        if (parts.length >= 2 && parts[1].equals(Identifier.DEFAULT_NAMESPACE)) {
            return true;
        }
        return builder.getDataOrSource(path) != null;
    }

    private static boolean isSoundEventReference(JsonElement sound) {
        if (!sound.isJsonObject()) {
            return false;
        }
        JsonElement type = sound.getAsJsonObject().get("type");
        return type != null && type.isJsonPrimitive() && type.getAsJsonPrimitive().isString()
            && "event".equals(type.getAsString());
    }

    /** Resolves a sounds.json entry to the resource-pack path of its .ogg, or null for malformed data. */
    private static @org.jetbrains.annotations.Nullable String soundFilePath(String tableNamespace, JsonElement sound) {
        try {
            String name;
            if (sound.isJsonPrimitive() && sound.getAsJsonPrimitive().isString()) {
                name = sound.getAsString();
            } else if (sound.isJsonObject() && sound.getAsJsonObject().has("name")) {
                name = sound.getAsJsonObject().get("name").getAsString();
            } else {
                return null;
            }
            Identifier id = name.indexOf(':') >= 0
                ? Identifier.parse(name)
                : Identifier.fromNamespaceAndPath(tableNamespace, name);
            String suffix = id.getPath().endsWith(".ogg") ? "" : ".ogg";
            return "assets/" + id.getNamespace() + "/sounds/" + id.getPath() + suffix;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Leaves out files that are neither drawn, played nor read by anything on a client.
     * <p>
     * Every file in the pack is one more for a joining client to index before the world appears, and this
     * server's pack has sixty thousand of them. These ones are not assets at all: they are a mod's own prose,
     * kept where its own code reads it. Left out, a client that has the mod falls back to the mod's copy and
     * a client that does not have it loses nothing, because it had nothing to read them with.
     */
    private static void leaveOutUnreadableText(ResourcePackBuilder builder) {
        if (!ConfigManager.config().resources.excludeUnreadableText) {
            return;
        }

        int[] left = {0};
        builder.addResourceConverter((path, resource) -> {
            if (!path.endsWith(".txt") && !path.endsWith(".md")) {
                return resource;
            }
            left[0]++;
            return null;
        });
    }

    private static boolean isBackgroundSound(String path, List<String> folders) {
        if (!path.startsWith("assets/")) {
            return false;
        }

        // "assets/<mod>/sounds/<folder>/..." - and never "assets/<mod>/sounds.json", which is the
        // list of what the sounds are called and has to stay
        int sounds = path.indexOf("/sounds/");
        if (sounds < 0) {
            return false;
        }

        String beneath = path.substring(sounds + "/sounds/".length());
        // A record is user-triggered gameplay audio, not background music. Keep it even when a mod
        // stores it below the otherwise-excluded music directory. These are the conventional names
        // used by vanilla and mod loaders; matching path segments keeps this independent of any
        // particular mod while avoiding the many-megabyte biome/structure soundtrack directories.
        String normalized = "/" + beneath.toLowerCase(Locale.ROOT).replace('\\', '/') + "/";
        if (ConfigManager.config().resources.includeMusicDiscs
            && (normalized.contains("/jukebox/")
            || normalized.contains("/record/") || normalized.contains("/records/")
            || normalized.contains("/music_disc/") || normalized.contains("/music_discs/"))) {
            return false;
        }

        for (String folder : folders) {
            if (beneath.startsWith(folder + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes the missing item definitions for models a mod put under the game's own name.
     * <p>
     * A block this mod could not give a real vanilla carrier is drawn by a display entity instead, and a
     * display entity is handed an <em>item</em> - which finds its model only through a definition file of
     * its own. Those definitions are written for a mod when its folders are bridged, and the bridging is
     * done per mod namespace. Nothing bridges {@code minecraft}, for the very good reason that doing so
     * would write a definition for every block the game ships.
     * <p>
     * Which leaves a mod that registers its content under {@code minecraft} - FallDrop Backport does,
     * because it is adding what the next version will ship - with models in the pack that nothing points
     * at. That is why its wool and concrete stairs arrived untextured: the model was there, the display
     * was there, and the one file joining them was not. It is the same gap the void lachryma fell into.
     * <p>
     * So only the models the game itself does not have are bridged. Asked of the game's own jar rather
     * than by name, so a real vanilla model is never given a second definition.
     */
    /**
     * Points sign models at a template that actually exists.
     * <p>
     * Enderscape ships its signs as models inheriting {@code minecraft:block/template_sign_rot_0} and
     * its neighbours - thirty-six of them - and the game has no such models. Vanilla draws a standing
     * sign entirely from its block entity renderer, so the only sign templates it ships at all are the
     * two wall ones; the rest were removed long before this version. Every one of those models is
     * therefore an orphan, and a model whose parent is missing has no geometry: the sign is there, and
     * nothing is drawn.
     * <p>
     * factorytools ships the templates Enderscape is reaching for, under a name of its own, because
     * this is exactly the gap it exists to fill. So a sign model left pointing at a template the game
     * does not have is pointed at the matching factorytools one instead. Only orphans are touched - a
     * parent that resolves is left exactly as it is.
     */
    private static void repairOrphanedSignTemplates(ResourcePackBuilder builder) {
        Map<String, byte[]> repaired = new LinkedHashMap<>();

        builder.forEachResource((path, resource) -> {
            if (!path.endsWith(".json") || !path.contains("/models/")) {
                return;
            }
            try {
                byte[] data = builder.getData(path);
                if (data == null) {
                    return;
                }
                ModelAsset asset = ModelAsset.fromJson(new String(data, StandardCharsets.UTF_8));
                if (asset.parent().isEmpty()) {
                    return;
                }

                Identifier parent = asset.parent().get();
                if (!parent.getNamespace().equals("minecraft") || !parent.getPath().contains("sign")) {
                    return;
                }
                // Only what the game genuinely cannot answer for
                if (builder.getDataOrSource(AssetPaths.model(parent) + ".json") != null
                    || ResourceHelper.hasVanillaAsset("minecraft", "models/" + parent.getPath() + ".json")) {
                    return;
                }

                Identifier replacement = factoryToolsSignTemplate(parent.getPath());
                repaired.put(path, new ModelAsset(Optional.of(replacement), asset.elements(), asset.textures(),
                    asset.display(), asset.guiLight(), asset.ambientOcclusion()).toBytes());
            } catch (Throwable e) {
                PolymerPatcher.LOGGER.debug("Could not look at {} for an orphaned sign template", path, e);
            }
        });

        repaired.forEach(builder::addData);
        if (!repaired.isEmpty()) {
            PolymerPatcher.LOGGER.info("{} sign model(s) inherited a template this version of the game does not ship; they now use the factorytools one", repaired.size());
        }
    }

    /** The factorytools template that matches a vanilla sign template's name. */
    private static Identifier factoryToolsSignTemplate(String vanillaPath) {
        String template;
        if (vanillaPath.contains("wall_hanging_sign")) {
            template = "template_wall_hanging_sign";
        } else if (vanillaPath.contains("wall_sign")) {
            template = "template_wall_sign";
        } else if (vanillaPath.contains("hanging_sign")) {
            // Covers the attached form too; both hang, and the attachment is the blockstate's business
            template = "template_hanging_sign";
        } else {
            template = "template_sign";
        }
        return Identifier.fromNamespaceAndPath("factorytools", "block_sign/" + template);
    }

    private static void bridgeModelsRegisteredAsTheGamesOwn(ResourcePackBuilder builder) {
        String prefix = "assets/" + Identifier.DEFAULT_NAMESPACE + "/models/";
        List<String> theirs = new ArrayList<>();

        // Collected first, because adding to the pack while walking it is not allowed
        builder.forEachResource((path, resource) -> {
            if (!path.startsWith(prefix) || !path.endsWith(".json")) {
                return;
            }

            String model = path.substring(prefix.length(), path.length() - ".json".length());
            if (!model.startsWith("block/") || ResourceHelper.hasVanillaAsset(Identifier.DEFAULT_NAMESPACE, "models/" + model + ".json")) {
                return;
            }
            theirs.add(model);
        });

        for (String model : theirs) {
            builder.addData("assets/" + Identifier.DEFAULT_NAMESPACE + "/items/-/" + model + ".json",
                new ItemAsset(new BasicItemModel(
                    Identifier.fromNamespaceAndPath(Identifier.DEFAULT_NAMESPACE, model),
                    List.of(new MapColorTintSource(0xFFFFFF))), ItemAsset.Properties.DEFAULT));
        }

        if (!theirs.isEmpty()) {
            PolymerPatcher.LOGGER.info("Wrote {} item definition(s) for models a mod registered under the game's own name", theirs.size());
        }
    }

    private static void expandModelAsset(ModelAsset asset, String path, ResourcePackBuilder builder) {
        builder.addData(path, new ModelAsset(asset.parent().map(identifier -> identifier.withSuffix("_expanded")), asset.elements().map(x -> x.stream()
            .map(element -> new ModelElement(element.from().subtract(EXPANSION), element.to().add(EXPANSION),
                pinnedFaces(element), element.rotation(), element.shade(), element.lightEmission())
            ).toList()), asset.textures(), asset.display(), asset.guiLight(), asset.ambientOcclusion()).toBytes());

        if (asset.parent().isPresent()) {
            var parentId = asset.parent().get();
            var parentAsset = ModelAsset.fromJson(new String(Objects.requireNonNull(builder.getDataOrSource(AssetPaths.model(parentId) + ".json")), StandardCharsets.UTF_8));

            expandModelAsset(parentAsset, AssetPaths.model(parentId.withSuffix("_expanded")) + ".json", builder);
        }
    }

    /**
     * An element's faces, with the part of the texture they were going to use written down.
     * <p>
     * A face that names no texture coordinates of its own gets them worked out from where the element
     * sits - and this element is about to be moved. Grown by the eight hundredths of a block that
     * keeps it from fighting whatever is behind it, a face that reached the edge of its texture now
     * reaches past it, and the game will not bake a quad that samples outside its own image:
     * {@code Cannot compute translucency out of bounds: [3, -1, 13, 17] in 16x16 image}. It refuses
     * the whole model, so the block is simply not drawn. Every emissive wall in Alex's Caves and
     * Enderscape went that way, which is what a crimson ivy on a wall was running into.
     * <p>
     * So the coordinates are worked out here, from the element as it stands before it grows, and
     * written down. The shape moves; what it samples stays where it was.
     */
    private static Map<net.minecraft.core.Direction, ModelElement.Face> pinnedFaces(ModelElement element) {
        Map<net.minecraft.core.Direction, ModelElement.Face> pinned = new EnumMap<>(net.minecraft.core.Direction.class);
        element.faces().forEach((direction, face) -> pinned.put(direction, face.uv().isEmpty()
            ? new ModelElement.Face(defaultUv(element.from(), element.to(), direction), face.texture(),
                face.cullface(), face.rotation(), face.tintIndex())
            : face));
        return pinned;
    }

    /** What the game works out for itself when a face says nothing, held inside the texture. */
    private static List<Float> defaultUv(Vec3 from, Vec3 to, net.minecraft.core.Direction direction) {
        float x1 = (float) from.x, y1 = (float) from.y, z1 = (float) from.z;
        float x2 = (float) to.x, y2 = (float) to.y, z2 = (float) to.z;

        float[] uv = switch (direction) {
            case DOWN -> new float[]{x1, 16 - z2, x2, 16 - z1};
            case UP -> new float[]{x1, z1, x2, z2};
            case NORTH -> new float[]{16 - x2, 16 - y2, 16 - x1, 16 - y1};
            case SOUTH -> new float[]{x1, 16 - y2, x2, 16 - y1};
            case WEST -> new float[]{z1, 16 - y2, z2, 16 - y1};
            case EAST -> new float[]{16 - z2, 16 - y2, 16 - z1, 16 - y1};
        };

        List<Float> held = new ArrayList<>(4);
        for (float one : uv) {
            held.add(Math.clamp(one, 0.0F, 16.0F));
        }
        return held;
    }

}
