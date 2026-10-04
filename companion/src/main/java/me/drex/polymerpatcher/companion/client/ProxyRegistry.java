package me.drex.polymerpatcher.companion.client;

import me.drex.polymerpatcher.companion.shared.CompanionManifest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns saved manifests into registered blocks and fluids while the game starts.
 * <p>
 * A name already taken is never overwritten. If a mod of that name is really installed, its own block
 * is far better than a copy; if another server's manifest registered it first, the two servers may
 * disagree about what it is. Either way the manifest that lost is not marked as registered, so that
 * server is told this client cannot use its blocks and shows the usual carriers instead.
 */
public final class ProxyRegistry {

    private ProxyRegistry() {
    }

    /** Every block registered, for the tints set up once the client is running. */
    static final List<ProxyBlock> BLOCKS = new ArrayList<>();
    /** Every fluid family registered, for the renderers set up once the client is running. */
    static final List<ProxyFluid.Family> FLUIDS = new ArrayList<>();

    /** What each registered name was registered as, so a second manifest can tell whether it agrees. */
    private static final Map<Identifier, String> SIGNATURES = new HashMap<>();

    public static void registerAll() {
        int blocks = 0;
        int fluids = 0;
        for (ManifestStore.Saved saved : ManifestStore.all()) {
            try {
                CompanionManifest manifest = CompanionManifest.fromBytes(saved.bytes());
                int before = BLOCKS.size();
                int fluidsBefore = FLUIDS.size();
                if (register(manifest, saved.server())) {
                    ManifestStore.markRegistered(saved.server(), saved.hash());
                }
                blocks += BLOCKS.size() - before;
                fluids += FLUIDS.size() - fluidsBefore;
            } catch (Throwable e) {
                CompanionMod.LOGGER.warn("Could not use the saved blocks of {}; that server will show its usual stand-ins", saved.server(), e);
            }
        }
        if (blocks > 0 || fluids > 0) {
            CompanionMod.LOGGER.info("Registered {} block(s) and {} fluid(s) from the servers this client has visited", blocks, fluids);
        }
    }

    /** @return whether every block and fluid in the manifest is now present exactly as described */
    private static boolean register(CompanionManifest manifest, String server) {
        ProxyStates.Tables tables = new ProxyStates.Tables(manifest);
        Map<Identifier, CompanionManifest.FluidEntry> fluidsByBlock = new HashMap<>();
        for (CompanionManifest.FluidEntry fluid : manifest.fluids) {
            fluidsByBlock.put(fluid.block(), fluid);
        }

        boolean complete = true;
        for (CompanionManifest.BlockEntry entry : manifest.blocks) {
            Identifier id = entry.id();
            String signature = signatureOf(entry);
            if (BuiltInRegistries.BLOCK.containsKey(id)) {
                if (!signature.equals(SIGNATURES.get(id))) {
                    complete = false;
                    CompanionMod.LOGGER.debug("{} from {} is already registered as something else", id, server);
                }
                continue;
            }
            if (!FabricLoader.getInstance().isModLoaded(id.getNamespace()) || "minecraft".equals(id.getNamespace())) {
                Pending pending = Pending.start();
                try {
                    CompanionManifest.FluidEntry fluid = fluidsByBlock.get(id);
                    if (fluid != null) {
                        registerFluid(fluid, entry, tables);
                    } else {
                        // Built in full before anything is registered, so a failure leaves nothing half done
                        ProxyBlock block = ProxyBlock.create(id, new ProxyStates(entry, tables));
                        Registry.register(BuiltInRegistries.BLOCK, id, block);
                        BLOCKS.add(block);
                    }
                    SIGNATURES.put(id, signature);
                } catch (Throwable e) {
                    pending.undo();
                    complete = false;
                    CompanionMod.LOGGER.warn("Could not register a copy of {} from {}", id, server, e);
                }
            } else {
                // The mod itself is installed but did not register this block; the two cannot both be right
                complete = false;
            }
        }
        return complete;
    }

    private static void registerFluid(CompanionManifest.FluidEntry entry, CompanionManifest.BlockEntry blockEntry, ProxyStates.Tables tables) {
        if (BuiltInRegistries.FLUID.containsKey(entry.source()) || BuiltInRegistries.FLUID.containsKey(entry.flowing())) {
            throw new IllegalStateException("The fluids of " + entry.block() + " are already registered");
        }
        // All three are built before any is registered, so the fluid never exists without its block
        ProxyFluid.Family family = new ProxyFluid.Family(entry);
        family.source = new ProxyFluid.Source(family);
        family.flowing = new ProxyFluid.Flowing(family);
        ProxyLiquidBlock block = ProxyLiquidBlock.create(entry.block(), family.source, new ProxyStates(blockEntry, tables));
        family.block = block;
        Registry.register(BuiltInRegistries.FLUID, entry.source(), family.source);
        Registry.register(BuiltInRegistries.FLUID, entry.flowing(), family.flowing);
        Registry.register(BuiltInRegistries.BLOCK, entry.block(), block);
        FLUIDS.add(family);
    }

    /**
     * What the registries had waiting before an attempt, so a failed attempt can be taken back.
     * <p>
     * Building a block or fluid adds it to its registry's waiting list straight away, and the game will
     * not finish starting while anything is left there. Whatever a failed attempt added is removed.
     */
    private record Pending(java.util.Set<Object> blocks, java.util.Set<Object> fluids) {
        static Pending start() {
            return new Pending(java.util.Set.copyOf(waiting(BuiltInRegistries.BLOCK).keySet()),
                java.util.Set.copyOf(waiting(BuiltInRegistries.FLUID).keySet()));
        }

        void undo() {
            try {
                waiting(BuiltInRegistries.BLOCK).keySet().removeIf(entry -> !blocks.contains(entry));
                waiting(BuiltInRegistries.FLUID).keySet().removeIf(entry -> !fluids.contains(entry));
            } catch (Throwable e) {
                CompanionMod.LOGGER.error("Could not take back a failed copy; the game may refuse to start", e);
            }
        }

        @SuppressWarnings("unchecked")
        private static Map<Object, ?> waiting(Registry<?> registry) {
            Map<Object, ?> map = ((me.drex.polymerpatcher.companion.client.mixin.MappedRegistryAccessor<Object>) registry)
                .polymerPatcherClient$unregisteredIntrusiveHolders();
            return map == null ? new HashMap<>() : map;
        }
    }

    /** The property names and values of a block, which is all two copies have to agree on to swap correctly. */
    private static String signatureOf(CompanionManifest.BlockEntry entry) {
        StringBuilder signature = new StringBuilder();
        for (CompanionManifest.PropertyEntry property : entry.properties()) {
            signature.append(property.name()).append('=').append(property.values()).append(';');
        }
        return signature.toString();
    }

    static boolean isCopy(Block block) {
        return block instanceof ProxyBlock || block instanceof ProxyLiquidBlock;
    }
}
