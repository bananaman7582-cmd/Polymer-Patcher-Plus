package me.drex.polymerpatcher.registry;

import me.drex.polymerpatcher.util.BlockSyncCheck;
import me.drex.polymerpatcher.block.CarrierStateSafety;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.Set;

/** Regression check for block states serialized by raw numeric id in Polymer's block sync packet. */
public final class BlockSyncSafetyCheck {

    private BlockSyncSafetyCheck() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        Identifier stone = Identifier.fromNamespaceAndPath("minecraft", "stone");
        Identifier backported = Identifier.fromNamespaceAndPath("minecraft", "polymer_patcher_fake_future_block");
        Identifier modded = Identifier.fromNamespaceAndPath("the_sift", "ichor");

        Set<Identifier> vanillaAssets = Set.of(stone);

        expect(RegistryPatcher.isVanillaBlock(stone, vanillaAssets::contains),
            "a real vanilla block must remain readable");
        expect(RegistryPatcher.isVanillaId(backported), "the regression fixture must use the vanilla namespace");
        expect(!RegistryPatcher.isVanillaBlock(backported, vanillaAssets::contains),
            "a mod-added minecraft: block must not be mistaken for a vanilla block");
        expect(!RegistryPatcher.isVanillaBlock(modded, vanillaAssets::contains),
            "an ordinary modded block must not be readable raw");
        expect(!BlockSyncCheck.preservesArbitraryRawStateIds(),
            "block metadata must never preserve server-local raw state ids");

        var dryStair = Blocks.OAK_STAIRS.defaultBlockState();
        var wetStair = dryStair.setValue(BlockStateProperties.WATERLOGGED, true);
        expect(CarrierStateSafety.isWaterSafeCarrier(dryStair, dryStair),
            "a dry stair may use a dry carrier");
        expect(!CarrierStateSafety.isWaterSafeCarrier(dryStair, wetStair),
            "a dry stair must never use a waterlogged carrier");
        expect(CarrierStateSafety.isWaterSafeCarrier(wetStair, dryStair),
            "a wet source may degrade to a dry carrier when the wet pool is exhausted");
        expect(CarrierStateSafety.isWaterSafeCarrier(wetStair, wetStair),
            "a wet source may use a wet carrier");
        expect(Block.BLOCK_STATE_REGISTRY.size() == BlockSyncCheck.VANILLA_26_2_STATE_COUNT,
            "the embedded 26.2 client boundary must match the pristine game registry (expected "
                + BlockSyncCheck.VANILLA_26_2_STATE_COUNT + ", found " + Block.BLOCK_STATE_REGISTRY.size() + ")");
        for (int rawId = 0; rawId < Block.BLOCK_STATE_REGISTRY.size(); rawId++) {
            var state = Block.BLOCK_STATE_REGISTRY.byId(rawId);
            expect(state != null, "pristine state " + rawId + " must exist");
            expect(BlockSyncCheck.isClientReadableWorldState(state),
                "pristine state " + rawId + " must be present in the embedded semantic map");
            expect(BlockSyncCheck.clientRawId(state) == rawId,
                "semantic state " + BlockSyncCheck.stateKey(state) + " must map to client id " + rawId
                    + " (found " + BlockSyncCheck.clientRawId(state) + ")");
            expect(BlockSyncCheck.clientReadableWorldState(state) == state,
                "an unmodified registry must map pristine state " + rawId + " back to itself");

            // A mod may hang a property of its own on a block the game ships - The Sift puts an
            // ichorlogged beside every waterlogged - and the server's copy of that block then carries a
            // name no client has. Keys are therefore written from what the client knows this block by,
            // and that set has to be exactly the properties of the block's own pristine key. Read it
            // wrong and every affected block is sent as a barrier: invisible leaves, invisible chests.
            String key = BlockSyncCheck.stateKey(state);
            int open = key.indexOf('[');
            java.util.Set<String> known = BlockSyncCheck.clientPropertiesOf(open < 0 ? key : key.substring(0, open));
            expect(known != null, "every pristine block must be known by name: " + key);
            java.util.Set<String> own = new java.util.HashSet<>();
            if (open >= 0) {
                for (String pair : key.substring(open + 1, key.length() - 1).split(",")) {
                    own.add(pair.substring(0, pair.indexOf('=')));
                }
            }
            expect(known.equals(own), "the client's properties for " + key + " must be exactly its own: " + known);
            expect(!known.contains("ichorlogged"), "a property no client has must never be counted as known");

            // On an unmodified registry every state is one a client has, so the carrier filter must take
            // nothing away - it exists only to remove the server-only twins a mod adds
            expect(BlockSyncCheck.usableAsCarrier(state), "pristine state " + key + " must stay usable as a carrier");
            expect(BlockSyncCheck.asClientTwin(state) == state,
                "pristine state " + key + " has nothing a client lacks, so it must go to Polymer untouched");
        }

        // The pack's blockstate keys, put into a client's terms. A property the client has not got is
        // taken out where it names the default, and the whole rule dropped where it names anything else,
        // because the client throws on the unknown name and would otherwise discard the entire file
        java.util.Set<String> unknown = java.util.Set.of("ichorlogged");
        java.util.Map<String, String> defaults = java.util.Map.of("ichorlogged", "false", "waterlogged", "false");
        expect("distance=1,persistent=true,waterlogged=false".equals(me.drex.polymerpatcher.resources.ClientBlockStateKeys
                .readableKey("distance=1,ichorlogged=false,persistent=true,waterlogged=false", unknown, defaults)),
            "a key naming an unknown property at its default keeps everything else");
        expect(me.drex.polymerpatcher.resources.ClientBlockStateKeys
                .readableKey("distance=1,ichorlogged=true,persistent=true,waterlogged=false", unknown, defaults) == null,
            "a key naming an unknown property at anything but its default describes no client state");
        expect("".equals(me.drex.polymerpatcher.resources.ClientBlockStateKeys.readableKey("", unknown, defaults)),
            "the empty key, meaning every state, is left alone");
        expect("facing=east,half=bottom".equals(me.drex.polymerpatcher.resources.ClientBlockStateKeys
                .readableKey("facing=east,half=bottom", unknown, defaults)),
            "a key with nothing unknown in it is left alone");

        System.out.println("Verified all " + Block.BLOCK_STATE_REGISTRY.size()
            + " semantic Polymer block states map to the pristine client registry");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("Block sync safety check failed: " + message);
        }
    }
}
