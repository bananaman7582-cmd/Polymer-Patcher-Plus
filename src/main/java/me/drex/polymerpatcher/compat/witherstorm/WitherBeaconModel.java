package me.drex.polymerpatcher.compat.witherstorm;

import eu.pb4.factorytools.api.virtualentity.BlockModel;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import me.drex.polymerpatcher.util.NativeClients;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Brightness;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the withered beacon and the withered support beacon draw with their block renderer, drawn with displays.
 * <p>
 * The blocks themselves have ordinary models and showed up fine. Everything that makes them beacons did
 * not: the spinning crystal, the items circling the main beacon, the beams joining each support beacon to
 * it, and the great beam into the sky. All of that is worked out from state the server already keeps on
 * the block entity, so it is read off that every tick and the displays follow it.
 * <p>
 * Players with the mod are shown this too. A block always reaches a client as one of the game's own, mod
 * or no mod, so their client has no beacon block entity for its renderer to draw - the crystal and the
 * joining beams were missing for them as well. The beam into the sky is the exception: the mod sends that
 * to them separately and draws it itself, so they are left out of that one.
 */
final class WitherBeaconModel extends BlockModel {
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();

    private final BlockPos pos;
    private final boolean main;
    private final ItemDisplayElement[] crystal = new ItemDisplayElement[3];
    private final List<ItemDisplayElement> orbiting = new ArrayList<>();
    private final ItemDisplayElement joinBeam;
    private final ItemDisplayElement beam;
    private final ItemDisplayElement beamGlow;
    private @Nullable String crystalColor;
    private boolean lastBright = true;

    WitherBeaconModel(BlockPos pos, boolean main) {
        this.pos = pos.immutable();
        this.main = main;
        for (int i = 0; i < crystal.length; i++) {
            crystal[i] = display(WitherStormCompatibility.crystalModel(main ? null : "iron"));
            crystal[i].setBrightness(new Brightness(15, 15));
            crystal[i].setInterpolationDuration(1);
            addElement(crystal[i]);
        }
        joinBeam = beamDisplay(WitherStormCompatibility.JOIN_BEAM);
        beam = beamDisplay(WitherStormCompatibility.SKY_BEAM);
        beamGlow = beamDisplay(WitherStormCompatibility.SKY_BEAM_GLOW);
        // The mod draws its own sky beam for those who have it
        beam.setVisibilityPredicate(player -> !NativeClients.carries(player, WitherStormPackets.MOD_ID));
        beamGlow.setVisibilityPredicate(player -> !NativeClients.carries(player, WitherStormPackets.MOD_ID));
        addElement(joinBeam);
        addElement(beam);
        addElement(beamGlow);
    }

    private static ItemDisplayElement display(Identifier model) {
        ItemStack stack = new ItemStack(Items.PAPER);
        stack.set(DataComponents.ITEM_MODEL, model);
        ItemDisplayElement element = new ItemDisplayElement(stack);
        element.setItemDisplayContext(ItemDisplayContext.NONE);
        element.setViewRange(4);
        return element;
    }

    private static ItemDisplayElement beamDisplay(Identifier model) {
        ItemDisplayElement element = display(model);
        element.setBrightness(new Brightness(15, 15));
        element.setInterpolationDuration(1);
        element.setViewRange(16);
        hide(element);
        return element;
    }

    private static void hide(ItemDisplayElement element) {
        element.setTransformation(new Matrix4f().scale(0));
    }

    @Override
    protected void onTick() {
        BlockEntity entity = getAttachment() == null ? null : getAttachment().getWorld().getBlockEntity(pos);
        if (entity != null) {
            try {
                update(entity);
            } catch (Throwable ignored) {
                // A beacon that will not answer one tick keeps last tick's picture
            }
        }
        super.onTick();
    }

    private void update(BlockEntity entity) throws ReflectiveOperationException {
        float ticks = (int) call(entity, "getTicks");
        float anim = (float) call(entity, "getActivateAnimation", 1.0F);
        boolean active = (boolean) call(entity, "isActive");
        int[] color = (int[]) call(entity, "getBeamColor");
        float r = Mth.lerp(anim, 0.5F, color[0] / 255.0F);
        float g = Mth.lerp(anim, 0.5F, color[1] / 255.0F);
        float b = Mth.lerp(anim, 0.5F, color[2] / 255.0F);
        int beamRgb = net.minecraft.util.ARGB.colorFromFloat(1.0F, r, g, b) & 0xFFFFFF;

        // The crystal: three cubes turned 45 degrees about each axis, spinning together
        float pulse = (Mth.sin(ticks * 0.1F) + 10.0F);
        float scale = main ? Mth.lerp(anim, 0.4F, pulse * 0.05F) : Mth.lerp(anim, 0.2F, pulse * 0.035F);
        Matrix4f spin = new Matrix4f();
        if (main) {
            float speed = Mth.lerp(anim, 0.1F, 1.0F);
            spin.rotateX(Mth.DEG_TO_RAD * Mth.wrapDegrees(ticks) * 5.0F * speed)
                .rotateZ(-Mth.DEG_TO_RAD * Mth.wrapDegrees(ticks) * 12.0F * speed);
        } else {
            float speed = Mth.lerp(anim, 0.2F, 1.0F);
            spin.translate(0, 0.1F, 0)
                .rotateX(Mth.DEG_TO_RAD * Mth.wrapDegrees(ticks) * 4.0F * speed)
                .rotateY(-Mth.DEG_TO_RAD * Mth.wrapDegrees(ticks) * 8.0F * speed);
            Object beaconColor = call(entity, "getColor");
            String wanted = beaconColor == null ? "iron" : WitherStormCompatibility.textureFor(((Enum<?>) beaconColor).name());
            if (!wanted.equals(crystalColor)) {
                crystalColor = wanted;
                for (ItemDisplayElement cube : crystal) {
                    ItemStack stack = cube.getItem().copy();
                    stack.set(DataComponents.ITEM_MODEL, WitherStormCompatibility.crystalModel(wanted));
                    cube.setItem(stack);
                }
            }
        }
        for (int i = 0; i < crystal.length; i++) {
            Matrix4f cube = new Matrix4f(spin);
            switch (i) {
                case 0 -> cube.rotateX(Mth.PI / 4);
                case 1 -> cube.rotateY(Mth.PI / 4);
                default -> cube.rotateZ(Mth.PI / 4);
            }
            crystal[i].setTransformation(cube.scale(scale));
        }
        // Lit by the world once it is working, glowing in the dark until then, as the renderer has it
        if (active != !lastBright) {
            lastBright = !active;
            for (ItemDisplayElement cube : crystal) {
                cube.setBrightness(active ? null : new Brightness(15, 15));
            }
        }

        if (main) {
            updateOrbiting(entity, ticks);
        }

        // The beam joining a support beacon to the main one, and the main one's own beam up to where the
        // storm is being summoned back
        Vec3 from = null;
        Vec3 to = null;
        if (main) {
            Object resummoning = call(entity, "getResummonEntity");
            int resummonTicks = (int) call(entity, "getResummonTicks");
            if (resummoning != null && "witherstormmod:wither_storm".equals(String.valueOf(
                net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey((net.minecraft.world.entity.EntityType<?>) resummoning)))
                && resummonTicks > 60) {
                from = Vec3.ZERO;
                to = new Vec3(0, 3, 0);
            }
        } else {
            BlockPos target = (BlockPos) call(entity, "getBeamPos");
            if (target != null) {
                from = new Vec3(0, 0.1, 0);
                to = Vec3.atCenterOf(target).subtract(Vec3.atCenterOf(pos)).add(0, -0.1, 0);
            }
        }
        if (from != null && anim > 0.01F) {
            Vec3 along = to.subtract(from);
            float length = (float) along.length();
            Quaternionf turn = new Quaternionf().rotateTo(new Vector3f(0, 1, 0), along.toVector3f().normalize());
            joinBeam.setTransformation(new Matrix4f().translate(from.toVector3f()).rotate(turn)
                .scale(scale * 2, length, scale * 2).translate(0, 0.5F, 0));
            joinBeam.setDisplayHeight(length + 1);
            joinBeam.setDisplayWidth(length + 1);
            tint(joinBeam, beamRgb);
        } else {
            hide(joinBeam);
        }

        // The beam into the sky
        int height = (int) call(entity, "getBeamHeight");
        if (active && height > 0) {
            float thickness = (float) call(entity, "getThickness");
            float outer = (float) call(entity, "getOuterThickness");
            int skyRgb = net.minecraft.util.ARGB.colorFromFloat(1.0F, color[0] / 255.0F, color[1] / 255.0F, color[2] / 255.0F) & 0xFFFFFF;
            beam.setTransformation(new Matrix4f().scale(thickness * 2, height, thickness * 2).translate(0, 0.5F, 0));
            beamGlow.setTransformation(new Matrix4f().scale(outer * 2, height, outer * 2).translate(0, 0.5F, 0));
            for (ItemDisplayElement part : new ItemDisplayElement[]{beam, beamGlow}) {
                part.setDisplayHeight(height + 1);
                part.setDisplayWidth(2);
                tint(part, skyRgb);
            }
        } else {
            hide(beam);
            hide(beamGlow);
        }
    }

    /** The items put into the main beacon, circling above it. */
    private void updateOrbiting(BlockEntity entity, float ticks) {
        if (!(entity instanceof Container container)) {
            return;
        }
        int total = container.getContainerSize();
        while (orbiting.size() < total) {
            ItemDisplayElement item = new ItemDisplayElement(ItemStack.EMPTY);
            item.setItemDisplayContext(ItemDisplayContext.FIXED);
            item.setInterpolationDuration(1);
            item.setViewRange(2);
            orbiting.add(item);
            addElement(item);
        }
        while (orbiting.size() > total) {
            removeElement(orbiting.removeLast());
        }
        float baseRotation = ticks * 0.02F;
        float interval = Mth.TWO_PI / Math.max(1, total);
        for (int i = 0; i < total; i++) {
            ItemDisplayElement display = orbiting.get(i);
            ItemStack stack = container.getItem(i);
            if (!ItemStack.matches(stack, display.getItem())) {
                display.setItem(stack.copy());
            }
            float angle = interval * i + baseRotation;
            display.setTransformation(new Matrix4f()
                .translate(Mth.sin(angle), 1 + Mth.sin((ticks + i * total) * 0.2F) * 0.05F, Mth.cos(angle))
                .rotateY(-Mth.DEG_TO_RAD * (ticks + i * 100)));
        }
    }

    private static void tint(ItemDisplayElement element, int rgb) {
        DyedItemColor current = element.getItem().get(DataComponents.DYED_COLOR);
        if (current == null || current.rgb() != rgb) {
            ItemStack stack = element.getItem().copy();
            stack.set(DataComponents.DYED_COLOR, new DyedItemColor(rgb));
            element.setItem(stack);
        }
    }

    private static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        String key = target.getClass().getName() + "#" + name;
        Method method = METHODS.get(key);
        if (method == null) {
            method = args.length == 0 ? target.getClass().getMethod(name) : target.getClass().getMethod(name, float.class);
            METHODS.put(key, method);
        }
        return method.invoke(target, args);
    }
}
