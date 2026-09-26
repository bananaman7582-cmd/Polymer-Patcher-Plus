package me.drex.polymerpatcher.entity.armor;

import com.mojang.blaze3d.vertex.PoseStack;
import eu.pb4.factorytools.api.virtualentity.ItemDisplayElementUtil;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.data.DisplayEntityData;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.config.ConfigManager;
import me.drex.polymerpatcher.duck.IModelPart;
import me.drex.polymerpatcher.entity.render.ServerRenderStates;
import me.drex.polymerpatcher.mixin.client.ModelPartAccessor;
import me.drex.polymerpatcher.util.NativeClients;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Draws the armour a player is wearing that their own client cannot draw.
 * <p>
 * The player is a real entity and goes on rendering themselves; this only adds the pieces that would
 * otherwise be missing, as item displays riding along with them. Their skin, their held items and their
 * ordinary armour are left entirely alone.
 */
public class PlayerArmorModel extends ElementHolder {

    /** The slots armour is worn in, in the order the pieces are drawn. */
    private static final EquipmentSlot[] SLOTS = {
        EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };

    /** Said out loud once per piece, so a model that throws every tick does not fill the log. */
    private static final Set<Identifier> REPORTED_FAILURES = new HashSet<>();

    private final Player player;
    private final HumanoidRenderState state = new HumanoidRenderState();
    private final List<ItemDisplayElement> activeElements = new ArrayList<>();
    private int activeElementIndex = 0;

    public PlayerArmorModel(Player player) {
        this.player = player;
    }

    /**
     * Whether this player is wearing anything that needs drawing here at all.
     */
    public static boolean wearsAny(Player player) {
        for (EquipmentSlot slot : SLOTS) {
            if (needsDisplay(player.getItemBySlot(slot))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Display geometry is a last resort. A real equipment asset uses the client's own player model,
     * so it animates at render-frame speed, is hidden correctly in first person and appears in the
     * inventory preview.
     * <p>
     * What it cannot be is a shape. An equipment asset is a picture painted onto the body the client
     * already draws, and the darkness armour is a cloak: a cape down the back and four tails hanging off
     * it, none of which exist on a player's body. Painted flat, the cloak became a pattern on a shirt.
     * So a piece whose model carries parts of its own is drawn both ways - the asset for the body it fits,
     * and display geometry for the parts a body does not have.
     */
    private static boolean needsDisplay(ItemStack stack) {
        List<ArmorModels.Entry> entries = ArmorModels.getAll(stack);
        if (entries.isEmpty()) {
            return false;
        }
        if (!drawnByTheClient(stack)) {
            return true;
        }

        for (ArmorModels.Entry entry : entries) {
            if (!extraParts(entry.model()).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Whether the client draws this piece itself, from an equipment asset written into the pack. */
    private static boolean drawnByTheClient(ItemStack stack) {
        Equippable equippable = stack.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
        return equippable != null && equippable.assetId().isPresent();
    }

    /**
     * The parts of a model that are not part of an ordinary body.
     * <p>
     * Worked out once per model and kept by identity: these are built at start-up and shared by every piece
     * of a set, and this is asked for every wearer.
     */
    private static Set<ModelPart> extraParts(HumanoidModel<?> model) {
        return EXTRA_PARTS.computeIfAbsent(model, PlayerArmorModel::findExtraParts);
    }

    private static final java.util.Map<HumanoidModel<?>, Set<ModelPart>> EXTRA_PARTS =
        java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    private static Set<ModelPart> findExtraParts(HumanoidModel<?> model) {
        Set<ModelPart> extras = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        findExtraParts(model, model.root(), extras);
        return extras;
    }

    private static void findExtraParts(HumanoidModel<?> model, ModelPart part, Set<ModelPart> extras) {
        for (ModelPart child : ((ModelPartAccessor) (Object) part).getChildren().values()) {
            if (!isOrdinary(model, child) && !child.isEmpty()) {
                extras.add(child);
            }
            findExtraParts(model, child, extras);
        }
    }

    /** Whether this is one of the parts every player already has. */
    private static boolean isOrdinary(HumanoidModel<?> model, ModelPart part) {
        return part == model.head || part == model.hat || part == model.body
            || part == model.rightArm || part == model.leftArm
            || part == model.rightLeg || part == model.leftLeg;
    }

    @Override
    public boolean startWatching(ServerGamePacketListenerImpl connection) {
        // The server is never told whether this player is using first- or third-person. Even a hood or
        // cloak behind the body can cross the first-person camera while turning, so display-based
        // armour is never sent to its own wearer. Native humanoid equipment remains unaffected.
        if (connection.getPlayer() == player) {
            return false;
        }
        // A player with the mod draws this armour themselves, and would otherwise see it twice over
        if (NativeClients.hasAll(connection.getPlayer())) {
            return false;
        }
        return super.startWatching(connection);
    }

    @Override
    protected void onTick() {
        activeElementIndex = 0;

        try {
            render();
        } catch (Throwable e) {
            PolymerPatcher.LOGGER.debug("Failed to render armour", e);
        }

        while (activeElements.size() > activeElementIndex) {
            removeElement(activeElements.removeLast());
        }

        keepTheWearerInStep();
        super.onTick();
    }

    private void render() {
        extractState();

        for (EquipmentSlot slot : SLOTS) {
            ItemStack stack = player.getItemBySlot(slot);
            if (!needsDisplay(stack) || !ArmorRenderHooks.shouldRender(player, stack)) {
                continue;
            }

            for (ArmorModels.Entry entry : ArmorModels.getAll(stack)) {
                HumanoidModel<HumanoidRenderState> model = entry.model();
                try {
                    // The same answer the entity path gives: a mod layer may want the wearer back
                    if (state instanceof me.drex.polymerpatcher.duck.ICapturedEntity captured) {
                        captured.polymerPatcher$capture(player, 0F);
                    }
                    poseExtraParts(model, player);
                    model.setupAnim(state);
                } catch (Throwable e) {
                    if (REPORTED_FAILURES.add(entry.texture())) {
                        PolymerPatcher.LOGGER.warn("Failed to pose the armour model for {}; it will not be shown. "
                            + "This is reported once per piece.", entry.texture(), e);
                    }
                    continue;
                }
                // Where the pack gave this piece an equipment asset the client draws the body itself, and
                // what is left here is only what a body does not have
                boolean onlyExtras = drawnByTheClient(stack);
                setPartVisibility(model, slot, onlyExtras);

                PoseStack poseStack = new PoseStack();

                // ensure the element doesn't clip into nearby blocks
                EntityDimensions dimensions = player.getDimensions(player.getPose());
                poseStack.translate(0.0F, -dimensions.height() / 2, 0.0F);

                // The steps a living entity's renderer takes before it hands a model over, which is the
                // space every one of these models was built to sit in
                ServerRenderStates.setupRotations(state, poseStack, state.bodyRot);
                poseStack.scale(-1.0F, -1.0F, 1.0F);
                poseStack.translate(0.0F, -1.501F, 0.0F);

                walk(model.root(), poseStack, entry, slot, onlyExtras, false);
            }
        }
    }

    /**
     * Whether everything being drawn here is something a body does not have.
     * <p>
     * True for a cloak, whose body is drawn by the client from its equipment asset and whose cape and
     * tails are all that is left to us. False the moment anything is being drawn on the body itself.
     */
    private boolean wearsOnlyExtras() {
        if (!ConfigManager.config().entities.showYourOwnArmourExtras) {
            return false;
        }

        boolean any = false;
        for (EquipmentSlot slot : SLOTS) {
            ItemStack stack = player.getItemBySlot(slot);
            if (!needsDisplay(stack)) {
                continue;
            }
            if (!drawnByTheClient(stack)) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /**
     * Adds or drops the wearer as what they are wearing changes, so putting on a helmet with a shape of
     * its own takes their own view back off them rather than leaving it hanging in front of their eyes.
     */
    private void keepTheWearerInStep() {
        if (!(player instanceof net.minecraft.server.level.ServerPlayer wearer)) {
            return;
        }

        boolean watching = getWatchingPlayers().contains(wearer.connection);
        if (watching) {
            stopWatching(wearer);
        }
    }

    /**
     * Fills the state the armour models are posed from, straight off the player.
     */
    private void extractState() {
        ServerRenderStates.applyRotations(player, state);

        state.ageInTicks = player.tickCount;
        state.walkAnimationPos = player.walkAnimation.position();
        state.walkAnimationSpeed = player.walkAnimation.speed();
        state.attackTime = player.getAttackAnim(0);
        state.pose = player.getPose();
        state.scale = 1.0F;
        state.ageScale = 1.0F;
        state.isBaby = false;
        state.speedValue = 1.0F;
        state.mainArm = player.getMainArm();
        state.isCrouching = player.isCrouching();
        state.isFallFlying = player.isFallFlying();
        state.isVisuallySwimming = player.isVisuallySwimming();
        state.isPassenger = player.isPassenger();
        state.isUsingItem = player.isUsingItem();
        state.useItemHand = player.getUsedItemHand();
        state.ticksUsingItem = player.getTicksUsingItem();
        state.swimAmount = player.getSwimAmount(0);
    }

    /**
     * Lets a model with parts of its own put them where they belong, for the player wearing it.
     * <p>
     * A humanoid body is posed from the render state, and everything hanging off it - a cloak's cape and
     * tails, a hazmat mask - is not: those are the model's own, and the mod poses them itself in a method
     * of its own, which its layer calls just before drawing. Alex's Caves calls that {@code withAnimations},
     * and without it the cape keeps whatever angle the model file gave it while the body turns underneath.
     * <p>
     * Found by name because there is no interface to ask: a model that has no such method is simply posed
     * as an ordinary body, which is what happened to all of them before.
     */
    private static void poseExtraParts(HumanoidModel<?> model, Player wearer) {
        java.lang.reflect.Method pose = EXTRA_POSING.computeIfAbsent(model.getClass(), type -> {
            try {
                return type.getMethod("withAnimations", net.minecraft.world.entity.LivingEntity.class);
            } catch (Throwable e) {
                return NONE;
            }
        });

        if (pose == NONE) {
            return;
        }

        try {
            pose.invoke(model, wearer);
        } catch (Throwable e) {
            // Posed as an ordinary body, which is what it looked like before this existed
            EXTRA_POSING.put(model.getClass(), NONE);
        }
    }

    /** Stands for "this model has no posing of its own", so it is only looked for once. */
    private static final java.lang.reflect.Method NONE;

    static {
        try {
            NONE = Object.class.getMethod("toString");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final java.util.Map<Class<?>, java.lang.reflect.Method> EXTRA_POSING = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Shows only the parts of the body this slot covers, the way an armour layer does.
     */
    private static void setPartVisibility(HumanoidModel<?> model, EquipmentSlot slot, boolean onlyExtras) {
        model.head.visible = false;
        model.hat.visible = false;
        model.body.visible = false;
        model.rightArm.visible = false;
        model.leftArm.visible = false;
        model.rightLeg.visible = false;
        model.leftLeg.visible = false;

        switch (slot) {
            case HEAD -> {
                model.head.visible = true;
                model.hat.visible = true;
            }
            case CHEST -> {
                model.body.visible = true;
                model.rightArm.visible = true;
                model.leftArm.visible = true;
            }
            case LEGS -> {
                // Leggings ordinarily show the waist too, but a cape hangs off the body: shown for both, it
                // would be drawn twice over by anybody wearing the whole set
                model.body.visible = !onlyExtras;
                model.rightLeg.visible = true;
                model.leftLeg.visible = true;
            }
            case FEET -> {
                model.rightLeg.visible = true;
                model.leftLeg.visible = true;
            }
            default -> {
            }
        }

        // Left standing so what hangs off them is still reached, but not drawn: the client is drawing
        // those itself from the equipment asset
        model.head.skipDraw = onlyExtras;
        model.hat.skipDraw = onlyExtras;
        model.body.skipDraw = onlyExtras;
        model.rightArm.skipDraw = onlyExtras;
        model.leftArm.skipDraw = onlyExtras;
        model.rightLeg.skipDraw = onlyExtras;
        model.leftLeg.skipDraw = onlyExtras;
    }

    /**
     * Walks a posed model, giving every visible part with something in it an element of its own.
     */
    private void walk(ModelPart part, PoseStack poseStack, ArmorModels.Entry entry, EquipmentSlot slot,
                      boolean onlyExtras, boolean belowABodyPart) {
        if (!part.visible) {
            return;
        }

        boolean ordinary = isOrdinary(entry.model(), part);

        // A part hanging off nothing in particular - a cape kept at the root of the model rather than on the
        // body - belongs to the chest piece, which is the piece a cape is part of. Without this it would be
        // drawn again for every other piece of the set being worn
        if (onlyExtras && !ordinary && !belowABodyPart && slot != EquipmentSlot.CHEST) {
            return;
        }

        poseStack.pushPose();
        part.translateAndRotate(poseStack);

        if (!part.skipDraw && !part.isEmpty()) {
            int id = ((IModelPart) (Object) part).polymer_patcher$getId();
            ModelLayerLocation layer = ((IModelPart) (Object) part).polymer_patcher$getModelLayerLocation();
            if (id >= 0 && layer != null) {
                Identifier modelPath = entry.texture().withSuffix("/" + me.drex.polymerpatcher.entity.ModelLayerNames.of(layer) + "/part_" + id);
                updateElement(ItemDisplayElementUtil.getModelCopy(modelPath), new Matrix4f(poseStack.last().pose()));
            }
        }

        for (ModelPart child : ((ModelPartAccessor) (Object) part).getChildren().values()) {
            walk(child, poseStack, entry, slot, onlyExtras, belowABodyPart || ordinary);
        }

        poseStack.popPose();
    }

    private void updateElement(ItemStack itemStack, Matrix4f matrix4f) {
        ItemDisplayElement element = activeElementIndex >= activeElements.size() ? null : activeElements.get(activeElementIndex);
        activeElementIndex++;

        if (element == null) {
            element = ItemDisplayElementUtil.createSimple(itemStack);
            element.setInterpolationDuration(1);
            element.setViewRange(2);
            // A display with no size bypasses vanilla's frustum culling. All armour pieces follow the
            // same player, so one player-sized box is both correct and much cheaper off screen.
            element.setDisplaySize(2.0F, 3.0F);
            EntityDimensions dimensions = player.getDimensions(player.getPose());
            element.setOffset(new Vec3(0, dimensions.height() / 2, 0));
            element.setTeleportDuration(0);
            addElement(element);
            activeElements.add(element);
        } else {
            element.setTeleportDuration(3);
            element.setItem(itemStack);
            element.getSyncedData().setDirty(DisplayEntityData.Item.ITEM, true);
        }

        element.setTransformation(matrix4f);
        element.startInterpolationIfDirty();
    }
}
