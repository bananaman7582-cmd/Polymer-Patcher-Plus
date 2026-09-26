package me.drex.polymerpatcher.item;

import me.drex.polymerpatcher.PolymerPatcher;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Watches an item move as it is held down, without being told anything about it.
 * <p>
 * A bow bends as it is drawn, a gauntlet folds as it charges, a crank turns as a gun fires. None of that
 * is in a model file: the mod poses the model in Java from a number it keeps on the item, and a client
 * without the mod has neither the Java nor any idea which number. What such a client does have is the
 * one thing a model file can be told to watch - how long the button has been held - and a list of shapes
 * to choose between as that number grows.
 * <p>
 * So the motion is measured. {@link ItemDataWatch} says which numbers the renderer reads off the item;
 * each is set to one tick, two ticks, three, and the item is drawn again each time; and the one that
 * moves it is the motion. What comes back is a shape per tick, which is exactly the list the model file
 * wants. Nothing here knows an item, a mod, or a name.
 * <p>
 * Knowing where a motion <em>ends</em> matters as much as knowing its shape, and cannot be seen from the
 * outside: a part turning steadily goes on turning past the end of the motion, because nothing in the
 * drawing stops at the point the mod would have stopped feeding it. What does stop is the number the
 * model is posed by - nought to one, one meaning finished - and that is heard directly, through
 * {@link me.drex.polymerpatcher.entity.citadel.CitadelDraw#noteProgress}.
 */
public final class HeldItemMotion {

    private HeldItemMotion() {
    }

    /**
     * One moment of a motion: the item as its renderer draws it after being held this long.
     *
     * @param pose     what the renderer had done to the pose by the time it drew
     * @param shape    where all the model's pieces were, for telling two moments apart
     * @param progress how far through the motion the model was told it was
     */
    public record Frame(int ticks, Matrix4f pose, float @Nullable [] shape, float progress) {
    }

    /**
     * A motion, as the number that carries it and what the item looks like at each tick of it.
     *
     * @param key    the item's own number the motion is kept in, named by the mod
     * @param frames one per tick from nought, the first being the item at rest and the last the end of
     *               the motion
     */
    public record Motion(String key, List<Frame> frames) {

        /** How long the whole motion takes, in ticks. */
        public int ticks() {
            return frames.get(frames.size() - 1).ticks();
        }
    }

    /** As long as a motion is watched for. Two and a half seconds is longer than any of these take. */
    private static final int LONGEST = 48;

    /** Movement smaller than this is nothing: floating point noise, or a part that barely stirs. */
    private static final float STILL = 1.0E-4F;

    /** How many unchanging ticks in a row mean a motion is over rather than merely pausing. */
    private static final int SETTLED = 4;

    /**
     * How an item moves while it is held, or null where nothing about it moves - which is most items,
     * and also every item whose motion runs on past the point this can see an end to.
     */
    public static @Nullable Motion measure(String namespace, ItemStack stack, ItemDisplayContext context) {
        HeldItemProbe.Drawn rest = HeldItemProbe.probe(namespace, stack, context);
        if (rest == null) {
            return null;
        }

        float[] restShape = CitadelItemModels.shape(rest.model());
        Set<String> keys = HeldItemProbe.dataKeys(namespace, stack, context);

        Motion furthest = null;
        float moved = STILL;

        for (String key : keys) {
            Motion motion = follow(namespace, stack, context, key, rest, restShape);
            if (motion == null) {
                continue;
            }

            float travelled = travelled(motion.frames(), rest, restShape);
            PolymerPatcher.LOGGER.debug("{} moves {} over the {} tick(s) its {} lasts",
                stack.getItem(), travelled, motion.ticks(), key);
            if (travelled > moved) {
                moved = travelled;
                furthest = motion;
            }
        }

        return furthest;
    }

    /**
     * The item drawn once per tick with this number set, for as long as the motion it carries lasts.
     * <p>
     * A motion ends either when the model says it has - the number it is posed by reaching one - or when
     * the drawing stops changing, which is what happens where the mod caps the number itself. Both are
     * facts about the item rather than anything anybody had to look up. Where neither happens the motion
     * has no end this can see, and is left alone rather than guessed at.
     *
     * @return null where this number does nothing to the item, or where it never stops doing it
     */
    private static @Nullable Motion follow(String namespace, ItemStack stack, ItemDisplayContext context,
                                           String key, HeldItemProbe.Drawn rest, float @Nullable [] restShape) {
        List<Frame> frames = new ArrayList<>();
        frames.add(new Frame(0, rest.pose(), restShape, rest.progress()));

        // A model that says it is already finished before anything has been held down is not talking
        // about this motion - it is posing something that never moves - and its word cannot be used
        boolean listenToProgress = rest.progress() < FINISHED;

        Matrix4f wasPose = rest.pose();
        float[] wasShape = restShape;
        int stillFor = 0;
        int lastMoved = 0;
        int finishedAt = 0;

        for (int ticks = 1; ticks <= LONGEST; ticks++) {
            HeldItemProbe.Drawn drawn = HeldItemProbe.drawnWith(namespace, stack, context, key, ticks);
            if (drawn == null) {
                break;
            }

            float[] shape = CitadelItemModels.shape(drawn.model());
            boolean moved = !drawn.pose().equals(wasPose, STILL)
                || CitadelItemModels.apart(shape, wasShape) > STILL;

            frames.add(new Frame(ticks, drawn.pose(), shape, drawn.progress()));
            wasPose = drawn.pose();
            wasShape = shape;

            if (moved) {
                lastMoved = ticks;
                stillFor = 0;
            } else if (++stillFor >= SETTLED) {
                break;
            }

            if (listenToProgress && drawn.progress() >= FINISHED && lastMoved > 0) {
                finishedAt = ticks;
                break;
            }
        }

        int ends = finishedAt > 0 ? finishedAt : (stillFor >= SETTLED ? lastMoved : 0);

        if (PolymerPatcher.LOGGER.isDebugEnabled()) {
            StringBuilder said = new StringBuilder();
            for (Frame frame : frames) {
                said.append(said.isEmpty() ? "" : " ").append(String.format(java.util.Locale.ROOT, "%d:%.2f",
                    frame.ticks(), frame.progress()));
            }
            PolymerPatcher.LOGGER.debug("{} by its {}: ends at {} (moved up to {}, said it was finished at {}); how "
                + "far through, tick by tick [{}]", stack.getItem(), key, ends, lastMoved, finishedAt, said);
        }

        if (ends <= 0) {
            return null;
        }

        return new Motion(key, List.copyOf(frames.subList(0, ends + 1)));
    }

    /** What the number a model is posed by reads when the motion it describes is over. */
    private static final float FINISHED = 1.0F - 1.0E-4F;

    /** The furthest the item gets from where it started, over the whole motion. */
    private static float travelled(List<Frame> frames, HeldItemProbe.Drawn rest, float @Nullable [] restShape) {
        float furthest = 0.0F;
        for (Frame frame : frames) {
            float apart = CitadelItemModels.apart(frame.shape(), restShape);
            furthest = Math.max(furthest, Math.max(apart, apart(frame.pose(), rest.pose())));
        }
        return furthest;
    }

    private static float apart(Matrix4f pose, Matrix4f other) {
        float[] one = new float[16];
        float[] two = new float[16];
        pose.get(one);
        other.get(two);
        return CitadelItemModels.apart(one, two);
    }

    /**
     * The moments of a motion worth keeping as shapes of their own.
     * <p>
     * A model file holds a handful of shapes and picks between them as the button is held, so a motion
     * measured tick by tick has to be thinned down to that handful. Which ticks are kept is decided by
     * the motion itself: the ones furthest from what the shapes either side of them would have shown,
     * taken one at a time until there are enough, so a motion that swings out and back gets a shape at
     * the top of the swing rather than an even scattering that misses it.
     *
     * @param most the most shapes to keep, the first of which is always the item at rest
     */
    public static List<Frame> stages(Motion motion, int most) {
        List<Frame> frames = motion.frames();
        List<Frame> kept = new ArrayList<>();
        kept.add(frames.get(0));
        if (frames.size() > 1) {
            // The end of the motion is always one of them. It is the shape shown for the whole time the
            // item is held at the end of its travel, which is most of the time anybody looks at it
            kept.add(frames.get(frames.size() - 1));
        }

        // A model file does not blend one shape into the next: at any moment it shows the last shape
        // whose turn has come, so each kept shape stands in for every tick from itself to the next one.
        // The stretch whose ticks are worst served is therefore the one to break in two, and breaking it
        // where the shape is half way from one end to the other halves how wrong it can be - which,
        // repeated, spreads the shapes along the motion by how much it is actually moving rather than by
        // the clock. A swing that pauses at the top gets a shape at the top
        while (kept.size() < most) {
            int worstStretch = -1;
            float worstBy = STILL;

            for (int stretch = 0; stretch + 1 < kept.size(); stretch++) {
                float wrong = wrongestIn(frames, kept.get(stretch), kept.get(stretch + 1).ticks());
                if (wrong > worstBy) {
                    worstBy = wrong;
                    worstStretch = stretch;
                }
            }

            if (worstStretch < 0) {
                break;
            }

            Frame from = kept.get(worstStretch);
            int until = kept.get(worstStretch + 1).ticks();
            Frame half = null;
            for (Frame frame : frames) {
                if (frame.ticks() > from.ticks() && frame.ticks() < until
                    && CitadelItemModels.apart(frame.shape(), from.shape()) >= worstBy / 2.0F) {
                    half = frame;
                    break;
                }
            }

            if (half == null) {
                break;
            }
            kept.add(worstStretch + 1, half);
        }

        return kept;
    }

    /** How far from this shape any of the ticks it stands in for gets. */
    private static float wrongestIn(List<Frame> frames, Frame from, int until) {
        float wrongest = 0.0F;
        for (Frame frame : frames) {
            if (frame.ticks() > from.ticks() && frame.ticks() < until) {
                wrongest = Math.max(wrongest, CitadelItemModels.apart(frame.shape(), from.shape()));
            }
        }
        return wrongest;
    }

    /** Says what was measured, for reading back against the item in the hand. */
    public static void report(String namespace, String itemPath, @Nullable Motion motion, List<Frame> stages) {
        if (motion == null) {
            PolymerPatcher.LOGGER.debug("{}:{} has no motion that can be measured", namespace, itemPath);
            return;
        }

        StringBuilder at = new StringBuilder();
        for (Frame stage : stages) {
            at.append(at.isEmpty() ? "" : ", ").append(stage.ticks());
        }

        PolymerPatcher.LOGGER.info("{}:{} moves over {} tick(s) as it is held, by its own {}; drawn at tick {}",
            namespace, itemPath, motion.ticks(), motion.key(), at);
    }

    /** How the item looked at one of the moments measured, drawn again so its shape can be read off. */
    public static @Nullable Object again(String namespace, ItemStack stack, ItemDisplayContext context,
                                         Motion motion, Frame stage) {
        if (stage.ticks() == 0) {
            HeldItemProbe.Drawn rest = HeldItemProbe.probe(namespace, stack, context);
            return rest == null ? null : rest.model();
        }

        HeldItemProbe.Drawn drawn = HeldItemProbe.drawnWith(namespace, stack, context, motion.key(), stage.ticks());
        return drawn == null ? null : drawn.model();
    }
}
