package me.drex.polymerpatcher.entity;

import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.TextDisplayElement;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Stands in for a mob this mod could not build a model for, so that it is something rather than
 * nothing.
 * <p>
 * Without this, an entity with no model is sent as an item display carrying no item, which is to say
 * it is invisible: it walks around, it can kill you, and there is nothing on screen. A
 * mob that renders as a marked box with its own name over it is worse looking and enormously better to
 * play against, and it says at a glance which entity is missing - which is the thing that otherwise
 * takes a server log and a guess to work out.
 */
public class PlaceholderModel extends HitboxModel {

    /** Roughly the height of the name above the box, in blocks. */
    private static final float LABEL_LIFT = 0.4F;

    private final Entity entity;
    private final ItemDisplayElement body;
    private final TextDisplayElement label;

    private float height = -1;

    public PlaceholderModel(Entity entity) {
        super(entity);
        this.entity = entity;

        this.body = new ItemDisplayElement(new ItemStack(Items.REDSTONE_BLOCK));
        this.body.setItemDisplayContext(ItemDisplayContext.FIXED);
        this.body.setViewRange(2);

        this.label = new TextDisplayElement(name(entity));
        this.label.setBillboardMode(net.minecraft.world.entity.Display.BillboardConstraints.CENTER);
        this.label.setViewRange(1);
        this.label.setShadow(true);

        this.addElement(this.body);
        this.addElement(this.label);
    }

    /**
     * The entity's registered name, which is the only thing worth reading off a placeholder.
     */
    private static Component name(Entity entity) {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return Component.literal(id != null ? id.toString() : entity.getType().toString())
            .withStyle(ChatFormatting.RED);
    }

    /**
     * Sized to the mob it stands for, so a placeholder for a whale is not the same box as one for a
     * hummingbird and the shape of what is missing is still readable.
     */
    @Override
    protected void onTick() {
        EntityDimensions dimensions = entity.getDimensions(entity.getPose());

        if (dimensions.height() != this.height) {
            this.height = dimensions.height();

            this.body.setScale(new Vector3f(dimensions.width(), dimensions.height(), dimensions.width()));
            this.body.setOffset(new Vec3(0, dimensions.height() / 2, 0));
            this.label.setOffset(new Vec3(0, dimensions.height() + LABEL_LIFT, 0));
        }

        super.onTick();
    }
}
