package me.drex.polymerpatcher.resources;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import eu.pb4.polymer.autohost.impl.ConnectionExt;
import eu.pb4.polymer.autohost.impl.providers.StandaloneWebServerProvider;
import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.network.Connection;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Hands each client a resource pack address it can actually reach.
 * <p>
 * Polymer's own hosting has one public address for everybody, which is fine for a server with one way
 * in and wrong for a server with several. This one is reached over Tailscale, over the local network,
 * and through a playit.gg tunnel, and no single address works for all three - a tunnel hostname means
 * nothing on the local network, and a local address means nothing to someone outside it. Whichever one
 * is configured, the other ways in get a pack they cannot download, and are disconnected for it.
 * <p>
 * The address a client typed to get here is already known - Polymer records it from the handshake to
 * build the same-port URL with - so it is used to decide what to send back. By default the pack is
 * offered on that same host at the hosting port, which is right for every way in that reaches the
 * machine directly. A tunnel is the exception, because its public port is unrelated to the one the
 * game is on, so those are named explicitly.
 * <p>
 * Everything else - the HTTP server, the port, the file handling - is Polymer's; only the choice of
 * address is made here, and any failure falls back to what Polymer would have said.
 */
public class PerClientPackProvider extends StandaloneWebServerProvider {

    /** Where the per-host addresses live inside this provider's {@code settings} block. */
    private static final String OVERRIDES = "address_overrides";

    /** An entry under this name is used for every client, whatever address it connected with. */
    private static final String ANY_HOST = "*";

    private static final String OVERRIDES_COMMENT =
        "Address to hand out per hostname the client connected with, for ways in whose public port differs "
            + "from the game's - a playit.gg or ngrok tunnel. Anything not listed is offered the pack on the "
            + "host it connected to, at the port above.";

    private int port = 25567;
    private Map<String, String> overrides = Map.of();

    @Override
    public void loadSettings(JsonElement settings) {
        super.loadSettings(settings);

        this.port = 25567;
        this.overrides = Map.of();

        if (settings == null || !settings.isJsonObject()) {
            return;
        }

        JsonObject object = settings.getAsJsonObject();

        try {
            if (object.has("port") && object.get("port").isJsonPrimitive()) {
                this.port = object.get("port").getAsInt();
            }
        } catch (RuntimeException e) {
            PolymerPatcher.LOGGER.warn("Could not read the resource pack hosting port; using {}", this.port, e);
        }

        try {
            if (object.has(OVERRIDES) && object.get(OVERRIDES).isJsonObject()) {
                Map<String, String> read = new HashMap<>();
                for (var entry : object.getAsJsonObject(OVERRIDES).entrySet()) {
                    if (!entry.getValue().isJsonPrimitive() || entry.getKey().startsWith("_")) {
                        continue;
                    }

                    String address = entry.getValue().getAsString();
                    // The example this ships with, left in place by someone who has not got as far as
                    // making the tunnel yet. Sending it out would be worse than sending nothing
                    if (address.contains("REPLACE")) {
                        PolymerPatcher.LOGGER.warn("Ignoring the resource pack address for {}: it is still the example. "
                            + "Put your tunnel's own address there, or remove the entry.", entry.getKey());
                        continue;
                    }

                    read.put(normalise(entry.getKey()), address);
                }
                this.overrides = Map.copyOf(read);
            }
        } catch (RuntimeException e) {
            PolymerPatcher.LOGGER.warn("Could not read the per-host resource pack addresses; none will be used", e);
        }
    }

    @Override
    public JsonElement saveSettings() {
        JsonElement saved = super.saveSettings();
        if (!saved.isJsonObject()) {
            return saved;
        }

        JsonObject object = saved.getAsJsonObject();
        object.addProperty("_c3", OVERRIDES_COMMENT);

        JsonObject written = new JsonObject();
        this.overrides.forEach(written::addProperty);
        object.add(OVERRIDES, written);

        return saved;
    }

    @Override
    protected String getAddress(Connection connection, String file) {
        try {
            String host = normalise(((ConnectionExt) connection).polymerAutoHost$getAddress());

            // An address for this exact way in, then one for everybody, then the host the client
            // reached the game on. The middle one exists for the case where the pack is not served off
            // this machine at all - a tunnel or a file host - where naming every way in separately
            // would be the same answer written out several times
            String override = this.overrides.get(host);
            if (override == null) {
                override = this.overrides.get(ANY_HOST);
            }
            if (override != null) {
                return withTrailingSlash(override) + file;
            }

            if (!host.isEmpty()) {
                return "http://" + host + ":" + this.port + "/" + file;
            }
        } catch (Throwable t) {
            // Whatever Polymer would have said is still better than nothing
            PolymerPatcher.LOGGER.debug("Could not work out a resource pack address for this connection", t);
        }

        return super.getAddress(connection, file);
    }

    /**
     * A hostname as it can be compared and put in a URL.
     * <p>
     * Lower-cased because hostnames are not case sensitive but map keys are, and stripped of the
     * trailing dot that a fully qualified name can carry - clients have been handed
     * {@code ply.gg.:22784} that way, which is a valid hostname and an awkward URL.
     */
    private static String normalise(String host) {
        if (host == null) {
            return "";
        }

        String trimmed = host.trim();
        while (trimmed.endsWith(".")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String withTrailingSlash(String address) {
        return address.endsWith("/") ? address : address + "/";
    }
}
