package me.drex.polymerpatcher.compat.borrowedecho;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.entity.render.RenderCaptureRules;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real player skins for Borrowed Echo's captured, animated player model.
 *
 * <p>A fake {@code PLAYER} carrier can wear a live profile skin, but it cannot perform Borrowed
 * Echo's custom model animation. An item display can animate the model, but it can only reference
 * textures present in the resource pack. The bridge therefore resolves the server's known profiles
 * before that pack is built, adds their skin PNGs as ordinary pack textures, and substitutes the
 * matching texture/model type into Borrowed Echo's render state.</p>
 */
public final class BorrowedEchoPlayerSkins {
    private static final String MOD_ID = "borrowed_echo";
    private static final int MAX_KNOWN_PLAYERS = 512;
    private static final int MAX_SKIN_BYTES = 1_048_576;
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
    private static final Map<UUID, SkinAsset> SKINS = new ConcurrentHashMap<>();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private static volatile @Nullable RenderAccess renderAccess;
    private static volatile boolean renderAccessUnavailable;

    private BorrowedEchoPlayerSkins() {
    }

    /** Registers the pack writer early; {@link #prepare} fills its data before Polymer invokes it. */
    public static void init() {
        if (INITIALIZED.compareAndSet(false, true)) {
            PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(BorrowedEchoPlayerSkins::writeAssets);
            // The renderer-state mixin selects slim/wide geometry when available. Enforce the texture
            // again at the generic captured-model boundary: an optional renderer hook may fail softly,
            // but a copied player must never silently fall back to Steve after their UUID skin was baked.
            RenderCaptureRules.registerEntityTexture(BorrowedEchoPlayerSkins::textureFor);
        }
    }

    /** Resolves and caches every profile the server currently knows. */
    public static void prepare(MinecraftServer server) {
        if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
            return;
        }

        SKINS.clear();
        Map<UUID, SkinSource> restored = readSkinRestorer(server);
        LinkedHashSet<UUID> known = readUserCache(server);
        known.addAll(restored.keySet());

        Path cache = server.getServerDirectory().resolve(".polymer-patcher").resolve("borrowed-echo-skins");
        int attempted = 0;
        int restoredCount = 0;
        for (UUID uuid : known) {
            if (attempted++ >= MAX_KNOWN_PLAYERS) {
                PolymerPatcher.LOGGER.warn("Borrowed Echo skin baking stopped after {} known profiles", MAX_KNOWN_PLAYERS);
                break;
            }

            SkinSource source = restored.get(uuid);
            if (source != null) {
                restoredCount++;
            } else {
                source = profileSkin(server, uuid);
            }
            if (source == null) {
                continue;
            }

            byte[] png = cachedOrDownload(cache, uuid, source);
            if (png == null) {
                continue;
            }
            remember(uuid, source.slim(), png);
        }

        PolymerPatcher.LOGGER.info(
            "Borrowed Echo baked {} real player skin(s) from {} known profile(s), including {} SkinRestorer override(s)",
            SKINS.size(), Math.min(known.size(), MAX_KNOWN_PLAYERS), restoredCount);
    }

    /** Adds UUID-specific skin model variants to Borrowed Echo's existing model layers. */
    public static Set<Identifier> addTextures(Set<Identifier> textures) {
        if (SKINS.isEmpty()) {
            return textures;
        }
        boolean missing = SKINS.values().stream().map(SkinAsset::texture).anyMatch(texture -> !textures.contains(texture));
        if (!missing) {
            return textures;
        }
        Set<Identifier> expanded = new LinkedHashSet<>(textures);
        for (SkinAsset skin : SKINS.values()) {
            expanded.add(skin.texture());
        }
        return expanded;
    }

    /**
     * Called after Borrowed Echo extracted its render state. The passive-creature path is left alone;
     * when it is drawing its humanoid model, both the selected texture and slim/wide model are replaced.
     */
    public static void apply(Entity echo, Object state, Object renderer) {
        try {
            RenderAccess fields = renderAccess(renderer, state);
            if (fields == null) {
                return;
            }

            // The renderer object is reused. If a copied player was slim, leaving its model installed
            // when the disguise died made the true/broken form inherit a body its anatomy was not baked
            // for; the lower half then vanished. Restore Borrowed Echo's ordinary wide model for every
            // event form before allowing its own renderer state to choose textures and animation.
            if (!BorrowedEchoCompat.wearsCopiedSkin(echo)
                || fields.creatureDisguise().get(state) != null) {
                fields.model().set(renderer, fields.wideModel().get(renderer));
                return;
            }

            // The snapped-neck form wears the copied skin like every other copied form, but its anatomy
            // was only ever baked for the wide body - a slim one there lost its lower half. So it keeps the
            // skin and is held to the wide model; a slim player's arms come out a pixel too wide, which is
            // a great deal better than the whole body turning into Steve
            boolean wideOnly = !BorrowedEchoCompat.usesCopiedPlayerSkin(echo);
            if (wideOnly) {
                fields.model().set(renderer, fields.wideModel().get(renderer));
            }

            UUID mimicked = BorrowedEchoCompat.mimickedUuid(echo);
            SkinAsset skin = mimicked == null ? null : SKINS.get(mimicked);
            if (skin == null) {
                return;
            }

            Object playerSkin = skin.playerSkin();
            if (playerSkin == null) {
                return;
            }
            fields.skin().set(state, playerSkin);
            boolean slim = skin.slim() && !wideOnly;
            fields.slim().setBoolean(state, slim);
            Object model = (slim ? fields.slimModel() : fields.wideModel()).get(renderer);
            fields.model().set(renderer, model);
        } catch (ReflectiveOperationException | RuntimeException e) {
            renderAccessUnavailable = true;
            PolymerPatcher.LOGGER.warn("Borrowed Echo changed its player render state; real profile skins are disabled", e);
        }
    }

    /** The UUID-specific pack texture for a stable copied-player form. */
    public static Identifier textureFor(Entity echo, Identifier fallback) {
        if (!BorrowedEchoCompat.wearsCopiedSkin(echo)) {
            return fallback;
        }
        UUID mimicked = BorrowedEchoCompat.mimickedUuid(echo);
        SkinAsset skin = mimicked == null ? null : SKINS.get(mimicked);
        return skin == null ? fallback : skin.texture();
    }

    private static void writeAssets(ResourcePackBuilder builder) {
        for (SkinAsset skin : SKINS.values()) {
            Identifier texture = skin.texture();
            builder.addData("assets/" + texture.getNamespace() + "/textures/" + texture.getPath() + ".png", skin.png());
        }
    }

    private static LinkedHashSet<UUID> readUserCache(MinecraftServer server) {
        LinkedHashSet<UUID> result = new LinkedHashSet<>();
        Path path = server.getServerDirectory().resolve("usercache.json");
        if (!Files.isRegularFile(path)) {
            return result;
        }
        try {
            JsonArray entries = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonArray();
            for (var element : entries) {
                JsonObject entry = element.getAsJsonObject();
                if (entry.has("uuid")) {
                    result.add(UUID.fromString(entry.get("uuid").getAsString()));
                }
            }
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.warn("Could not read known players from {}", path, e);
        }
        return result;
    }

    /** SkinRestorer is authoritative when it has replaced a player's Mojang profile texture. */
    private static Map<UUID, SkinSource> readSkinRestorer(MinecraftServer server) {
        Map<UUID, SkinSource> result = new LinkedHashMap<>();
        Path folder = server.getWorldPath(LevelResource.ROOT).resolve("skinrestorer");
        if (!Files.isDirectory(folder)) {
            return result;
        }
        try (var files = Files.list(folder)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(path -> {
                try {
                    String name = path.getFileName().toString();
                    UUID uuid = UUID.fromString(name.substring(0, name.length() - ".json".length()));
                    JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
                    JsonObject value = root.getAsJsonObject("value");
                    if (value == null || !value.has("value")) {
                        return;
                    }
                    JsonObject packed = JsonParser.parseString(new String(
                        Base64.getDecoder().decode(value.get("value").getAsString()), StandardCharsets.UTF_8)).getAsJsonObject();
                    SkinSource source = sourceFromPackedTextures(packed);
                    if (source != null) {
                        result.put(uuid, source);
                    }
                } catch (Throwable e) {
                    PolymerPatcher.LOGGER.debug("Could not read SkinRestorer skin {}", path, e);
                }
            });
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.warn("Could not inspect SkinRestorer profiles in {}", folder, e);
        }
        return result;
    }

    private static @Nullable SkinSource profileSkin(MinecraftServer server, UUID uuid) {
        try {
            var result = server.services().sessionService().fetchProfile(uuid, true);
            if (result == null) {
                return null;
            }
            MinecraftProfileTextures textures = server.services().sessionService().getTextures(result.profile());
            MinecraftProfileTexture skin = textures.skin();
            if (skin == null) {
                return null;
            }
            return safeSource(skin.getUrl(), "slim".equalsIgnoreCase(skin.getMetadata("model")));
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Could not resolve player skin {}", uuid, e);
            return null;
        }
    }

    private static @Nullable SkinSource sourceFromPackedTextures(JsonObject packed) {
        JsonObject textures = packed.getAsJsonObject("textures");
        JsonObject skin = textures == null ? null : textures.getAsJsonObject("SKIN");
        if (skin == null || !skin.has("url")) {
            return null;
        }
        JsonObject metadata = skin.getAsJsonObject("metadata");
        boolean slim = metadata != null && metadata.has("model")
            && "slim".equalsIgnoreCase(metadata.get("model").getAsString());
        return safeSource(skin.get("url").getAsString(), slim);
    }

    /** Only Mojang's immutable texture host is accepted, including SkinRestorer's signed proxies. */
    private static @Nullable SkinSource safeSource(String value, boolean slim) {
        try {
            URI uri = URI.create(value);
            if (!"textures.minecraft.net".equalsIgnoreCase(uri.getHost())) {
                return null;
            }
            if ("http".equalsIgnoreCase(uri.getScheme())) {
                uri = new URI("https", uri.getUserInfo(), uri.getHost(), uri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment());
            }
            return "https".equalsIgnoreCase(uri.getScheme()) ? new SkinSource(uri, slim) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static @Nullable byte[] cachedOrDownload(Path cache, UUID uuid, SkinSource source) {
        Path png = cache.resolve(uuid + ".png");
        Path metadata = cache.resolve(uuid + ".json");
        try {
            if (Files.isRegularFile(png) && Files.isRegularFile(metadata)) {
                JsonObject stored = JsonParser.parseString(Files.readString(metadata, StandardCharsets.UTF_8)).getAsJsonObject();
                if (stored.has("url") && source.uri().toString().equals(stored.get("url").getAsString())
                    && stored.has("slim") && source.slim() == stored.get("slim").getAsBoolean()) {
                    byte[] bytes = normalizePng(Files.readAllBytes(png));
                    if (bytes != null) {
                        return bytes;
                    }
                }
            }

            HttpRequest request = HttpRequest.newBuilder(source.uri())
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Polymer-Patcher/borrowed-echo-skins")
                .GET().build();
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body().length > MAX_SKIN_BYTES) {
                return null;
            }
            byte[] bytes = normalizePng(response.body());
            if (bytes == null) {
                return null;
            }

            Files.createDirectories(cache);
            Files.write(png, bytes);
            JsonObject stored = new JsonObject();
            stored.addProperty("url", source.uri().toString());
            stored.addProperty("slim", source.slim());
            Files.writeString(metadata, stored.toString(), StandardCharsets.UTF_8);
            return bytes;
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Could not cache Borrowed Echo skin {}", uuid, e);
            return null;
        }
    }

    /** Validates the PNG and expands legacy 64x32 skins to the modern 64x64 layout. */
    private static @Nullable byte[] normalizePng(byte[] input) {
        if (input.length == 0 || input.length > MAX_SKIN_BYTES) {
            return null;
        }
        try {
            BufferedImage read = ImageIO.read(new ByteArrayInputStream(input));
            if (read == null || read.getWidth() != 64 || (read.getHeight() != 64 && read.getHeight() != 32)) {
                return null;
            }
            BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = skin.createGraphics();
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(read, 0, 0, null);
            if (read.getHeight() == 32) {
                graphics.setColor(new Color(0, 0, 0, 0));
                graphics.fillRect(0, 32, 64, 32);
                // Vanilla's legacy conversion mirrors the old right limbs into the left-limb slots.
                graphics.drawImage(skin, 24, 48, 20, 52, 4, 16, 8, 20, null);
                graphics.drawImage(skin, 28, 48, 24, 52, 8, 16, 12, 20, null);
                graphics.drawImage(skin, 20, 52, 16, 64, 8, 20, 12, 32, null);
                graphics.drawImage(skin, 24, 52, 20, 64, 4, 20, 8, 32, null);
                graphics.drawImage(skin, 28, 52, 24, 64, 0, 20, 4, 32, null);
                graphics.drawImage(skin, 32, 52, 28, 64, 12, 20, 16, 32, null);
                graphics.drawImage(skin, 40, 48, 36, 52, 44, 16, 48, 20, null);
                graphics.drawImage(skin, 44, 48, 40, 52, 48, 16, 52, 20, null);
                graphics.drawImage(skin, 36, 52, 32, 64, 48, 20, 52, 32, null);
                graphics.drawImage(skin, 40, 52, 36, 64, 44, 20, 48, 32, null);
                graphics.drawImage(skin, 44, 52, 40, 64, 40, 20, 44, 32, null);
                graphics.drawImage(skin, 48, 52, 44, 64, 52, 20, 56, 32, null);
            }
            graphics.dispose();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            return ImageIO.write(skin, "png", output) ? output.toByteArray() : null;
        } catch (Throwable e) {
            return null;
        }
    }

    private static void remember(UUID uuid, boolean slim, byte[] png) {
        String name = uuid.toString().replace("-", "");
        Identifier texture = Identifier.fromNamespaceAndPath(
            PolymerPatcher.MOD_ID, "entity/borrowed_echo/player/" + name);
        SKINS.put(uuid, new SkinAsset(texture, slim, png));
    }

    /** Package-private verifier hooks; production uses the same map and texture construction. */
    static void rememberForTest(UUID uuid, boolean slim, byte[] png) {
        remember(uuid, slim, png);
    }

    static void clearForTest() {
        SKINS.clear();
    }

    static @Nullable Object playerSkinForTest(UUID uuid) {
        SkinAsset skin = SKINS.get(uuid);
        return skin == null ? null : skin.playerSkin();
    }

    static Identifier textureForUuidForTest(UUID uuid, Identifier fallback) {
        SkinAsset skin = SKINS.get(uuid);
        return skin == null ? fallback : skin.texture();
    }

    private static @Nullable RenderAccess renderAccess(Object renderer, Object state) throws ReflectiveOperationException {
        if (renderAccessUnavailable) {
            return null;
        }
        RenderAccess current = renderAccess;
        if (current != null) {
            return current;
        }
        current = new RenderAccess(
            field(state.getClass(), "skin"),
            field(state.getClass(), "slim"),
            field(state.getClass(), "creatureDisguise"),
            field(renderer.getClass(), "slimModel"),
            field(renderer.getClass(), "wideModel"),
            field(renderer.getClass(), "model")
        );
        renderAccess = current;
        return current;
    }

    private static Field field(Class<?> start, String name) throws NoSuchFieldException {
        for (Class<?> type = start; type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(start.getName() + "." + name);
    }

    private record SkinSource(URI uri, boolean slim) {
    }

    private record RenderAccess(Field skin, Field slim, Field creatureDisguise,
                                Field slimModel, Field wideModel, Field model) {
    }

    private static final class SkinAsset {
        private final Identifier texture;
        private final boolean slim;
        private final byte[] png;
        private volatile @Nullable Object playerSkin;

        private SkinAsset(Identifier texture, boolean slim, byte[] png) {
            this.texture = texture;
            this.slim = slim;
            this.png = png;
        }

        private Identifier texture() {
            return texture;
        }

        private boolean slim() {
            return slim;
        }

        private byte[] png() {
            return png;
        }

        private @Nullable Object playerSkin() {
            Object current = playerSkin;
            if (current != null) {
                return current;
            }
            try {
                Class<?> textureType = Class.forName("net.minecraft.core.ClientAsset$Texture");
                Object textureAsset = Proxy.newProxyInstance(textureType.getClassLoader(), new Class<?>[]{textureType},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "texturePath", "id" -> texture;
                        case "toString" -> texture.toString();
                        case "hashCode" -> texture.hashCode();
                        case "equals" -> proxy == (args == null ? null : args[0]);
                        default -> throw new UnsupportedOperationException(method.toString());
                    });
                Class<?> modelType = Class.forName("net.minecraft.world.entity.player.PlayerModelType");
                Object model = modelType.getField(slim ? "SLIM" : "WIDE").get(null);
                Class<?> playerSkinType = Class.forName("net.minecraft.world.entity.player.PlayerSkin");
                Method insecure = playerSkinType.getMethod("insecure", textureType, textureType, textureType, modelType);
                current = insecure.invoke(null, textureAsset, null, null, model);
                playerSkin = current;
                return current;
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
    }
}
