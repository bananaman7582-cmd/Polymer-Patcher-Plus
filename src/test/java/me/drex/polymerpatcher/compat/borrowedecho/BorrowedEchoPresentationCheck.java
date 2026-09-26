package me.drex.polymerpatcher.compat.borrowedecho;

import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityTypes;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Executable regression check for Borrowed Echo's hybrid player/anatomy resource contract. */
public final class BorrowedEchoPresentationCheck {
    private BorrowedEchoPresentationCheck() {
    }

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Identifier echo = Identifier.fromNamespaceAndPath("borrowed_echo", "borrowed_echo");
        Identifier skin = Identifier.fromNamespaceAndPath("minecraft", "entity/player/wide/steve");
        Identifier anatomy = Identifier.fromNamespaceAndPath("borrowed_echo", "entity/true_self");

        Set<Identifier> input = new LinkedHashSet<>(Set.of(skin));
        require(BorrowedEchoCompat.clientCarrier(null) == null,
            "Borrowed Echo renderer was bypassed by a vanilla carrier");
        require(!BorrowedEchoCompat.usesVanillaCarrier(null),
            "Borrowed Echo was classified as a wire creature/player");
        require(!BorrowedEchoCompat.hidesVirtualModel(null),
            "Borrowed Echo's animated captured model was hidden");
        require(BorrowedEchoCompat.allowCapturedTexture(null, skin),
            "Borrowed Echo's player-model skin layer was filtered");
        require(BorrowedEchoCompat.resolveVanillaEntityType("minecraft:cow") == EntityTypes.COW,
            "namespaced passive disguise did not resolve to its native renderer");
        require(BorrowedEchoCompat.resolveVanillaEntityType("cow") == EntityTypes.COW,
            "default-namespace passive disguise did not resolve");
        require(BorrowedEchoCompat.resolveVanillaEntityType("alexsmobs:bison") == null,
            "modded passive disguise was incorrectly exposed as a vanilla entity");
        require(BorrowedEchoCompat.resolveVanillaEntityType("minecraft:not a valid id") == null,
            "invalid passive disguise id escaped the safe parser");

        Set<Identifier> expanded = BorrowedEchoCompat.addEventTextures(echo, input);
        require(expanded.contains(skin), "player fallback texture was lost");
        require(expanded.contains(anatomy), "animated anatomy texture was not generated");
        require(!input.contains(anatomy), "caller-owned texture set was mutated");

        Set<Identifier> ordinary = BorrowedEchoCompat.addEventTextures(
            Identifier.fromNamespaceAndPath("borrowed_echo", "crucifix"), input);
        require(ordinary == input, "unrelated Borrowed Echo entity was modified");

        Set<Identifier> second = BorrowedEchoCompat.addEventTextures(echo, expanded);
        require(second == expanded, "already-expanded texture set was copied again");

        UUID player = UUID.fromString("890517e5-805c-4407-8ef4-26f185c32a11");
        Identifier realSkin = Identifier.fromNamespaceAndPath("polymer-patcher",
            "entity/borrowed_echo/player/890517e5805c44078ef426f185c32a11");
        BorrowedEchoPlayerSkins.rememberForTest(player, true, new byte[]{1, 2, 3});
        Set<Identifier> withPlayer = BorrowedEchoCompat.addEventTextures(echo, expanded);
        require(withPlayer.contains(realSkin), "known player's real skin was not added to the generated model variants");
        Object playerSkin = BorrowedEchoPlayerSkins.playerSkinForTest(player);
        require(playerSkin != null, "known player's runtime PlayerSkin was not constructed");
        require(realSkin.equals(BorrowedEchoPlayerSkins.textureForUuidForTest(player, skin)),
            "captured-model boundary did not select the UUID-specific baked skin");
        require(skin.equals(BorrowedEchoPlayerSkins.textureForUuidForTest(UUID.randomUUID(), skin)),
            "unknown profile did not retain the renderer's safe fallback texture");
        try {
            Object body = playerSkin.getClass().getMethod("body").invoke(playerSkin);
            Object path = body.getClass().getMethod("texturePath").invoke(body);
            Object model = playerSkin.getClass().getMethod("model").invoke(playerSkin);
            require(realSkin.equals(path), "runtime PlayerSkin points at the wrong baked texture");
            require("SLIM".equals(model.toString()), "runtime PlayerSkin lost the source profile's slim model type");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not inspect the runtime PlayerSkin", e);
        } finally {
            BorrowedEchoPlayerSkins.clearForTest();
        }
        System.out.println("Borrowed Echo presentation checks passed");
    }

    private static void require(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
