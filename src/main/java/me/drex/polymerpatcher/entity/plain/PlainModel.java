package me.drex.polymerpatcher.entity.plain;

import me.drex.polymerpatcher.mixin.client.ModelPartAccessor;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class PlainModel {
    private PlainModel() {
    }

    public static boolean isPlainModel(Object model) {
        return model instanceof EntityModel<?>;
    }

    /**
     * Whether any of these parts actually has cubes to draw.
     * <p>
     * A model is not worth registering if it is only a tree of empty parents: the pack would be
     * written with nothing in it and the mob would arrive as nothing at all, where the placeholder at
     * least said which mob it was.
     */
    public static boolean hasGeometry(List<ModelPart> parts) {
        for (ModelPart part : parts) {
            if (!part.isEmpty() && !((ModelPartAccessor) (Object) part).getCubes().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @NotNull
    public static List<ModelPart> roots(EntityModel<?> model) {
        Set<ModelPart> all = new LinkedHashSet<>();
        // Try root field first
        Field rootField = findField(model.getClass(), "root");
        if (rootField != null) {
            try {
                Object r = rootField.get(model);
                if (r instanceof ModelPart mp) {
                    collectAll(mp, all);
                    if (!all.isEmpty()) {
                        return new ArrayList<>(all);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        // Fallback: try to find any ModelPart field
        for (Field f : model.getClass().getDeclaredFields()) {
            if (ModelPart.class.isAssignableFrom(f.getType())) {
                try {
                    if (!f.trySetAccessible()) continue;
                    Object o = f.get(model);
                    if (o instanceof ModelPart mp) {
                        collectAll(mp, all);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return new ArrayList<>(all);
    }

    private static void collectAll(ModelPart part, Set<ModelPart> out) {
        out.add(part);
        try {
            for (ModelPart child : part.getAllParts()) {
                if (child != part) {
                    out.add(child);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static Field findField(Class<?> owner, String name) {
        for (Class<?> type = owner; type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}