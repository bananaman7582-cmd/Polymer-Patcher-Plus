package me.drex.polymerpatcher.sync;

import com.mojang.serialization.Codec;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.*;
import net.fabricmc.fabric.impl.attachment.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;

import java.util.IdentityHashMap;
import java.util.Map;

/** Opt-in real Fabric handler regression test. Exits the THROWAWAY server during initialization. */
public final class AttachmentCommandIntegration implements ModInitializer {
    private static final AttachmentType<Integer> PERSISTENT = AttachmentRegistry.create(
        Identifier.parse("attachment_command_test:persistent"), b -> b.persistent(Codec.INT));
    private static final AttachmentType<Integer> TRANSIENT = AttachmentRegistry.create(
        Identifier.parse("attachment_command_test:transient"));

    @Override
    public void onInitialize() {
        try {
            check();
            System.out.println("ATTACHMENT_COMMAND_INTEGRATION_PASS: 7 cases; lazy initialization, persistence, removals, and corruption guard");
            System.exit(0);
        } catch (Throwable error) {
            System.err.println("ATTACHMENT_COMMAND_INTEGRATION_FAIL");
            error.printStackTrace();
            System.exit(1);
        }
    }

    private static void check() {
        Target empty = new Target();
        DataAccessorHandler.applyDataChanges(empty, input(null), () -> {});
        require(empty.map == null, "No-op allocated attachment data");

        Target lazy = new Target();
        DataAccessorHandler.applyDataChanges(lazy, input(null), () -> lazy.setAttached(TRANSIENT, 7));
        require(Integer.valueOf(7).equals(lazy.getAttached(TRANSIENT)), "Lost lazy transient data");

        Target removed = new Target();
        DataAccessorHandler.applyDataChanges(removed, input(null), () -> removed.setAttached(PERSISTENT, 7));
        require(!removed.hasAttached(PERSISTENT) && removed.removals == 1, "Did not apply persistent removal via API");

        Target replaced = new Target();
        DataAccessorHandler.applyDataChanges(replaced, input(11), () -> replaced.setAttached(PERSISTENT, 7));
        require(Integer.valueOf(11).equals(replaced.getAttached(PERSISTENT)), "NBT did not win over lazy persistent default");

        Target existing = new Target();
        existing.setAttached(PERSISTENT, 3);
        existing.setAttached(TRANSIENT, 9);
        Map<AttachmentType<?>, ?> identity = existing.map;
        DataAccessorHandler.applyDataChanges(existing, input(12), () -> {});
        require(existing.map == identity && Integer.valueOf(12).equals(existing.getAttached(PERSISTENT)), "Existing map/update broken");
        DataAccessorHandler.applyDataChanges(existing, input(null), () -> {});
        require(!existing.hasAttached(PERSISTENT) && Integer.valueOf(9).equals(existing.getAttached(TRANSIENT)), "Removed transient data or kept removed persistent data");

        Target corrupt = new Target();
        corrupt.setAttached(TRANSIENT, 1);
        try {
            DataAccessorHandler.applyDataChanges(corrupt, input(null), () -> corrupt.map = new IdentityHashMap<>());
            throw new AssertionError("Allowed replacement of an existing attachment map");
        } catch (AssertionError expected) {
            require("Attachment data changed during data change application.".equals(expected.getMessage()), "Unexpected corruption error");
        }
    }

    private static ValueInput input(Integer value) {
        CompoundTag tag = new CompoundTag();
        if (value != null) {
            CompoundTag attachments = new CompoundTag();
            attachments.putInt(PERSISTENT.identifier().toString(), value);
            tag.put(AttachmentTarget.NBT_ATTACHMENT_KEY, attachments);
        }
        return TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, tag);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Target implements AttachmentTargetImpl {
        private IdentityHashMap<AttachmentType<?>, Object> map;
        private int removals;
        public Map<AttachmentType<?>, ?> fabric_getAttachments() { return map; }
        public RegistryAccess fabric_getRegistryAccess() { return RegistryAccess.EMPTY; }
        @SuppressWarnings("unchecked")
        public <T> T getAttached(AttachmentType<T> type) { return map == null ? null : (T) map.get(type); }
        public boolean hasAttached(AttachmentType<?> type) { return map != null && map.containsKey(type); }
        @SuppressWarnings("unchecked")
        public <T> T setAttached(AttachmentType<T> type, T value) {
            if (value == null) {
                removals++;
                return map == null ? null : (T) map.remove(type);
            }
            if (map == null) map = new IdentityHashMap<>();
            return (T) map.put(type, value);
        }
    }
}
