package me.drex.polymerpatcher.entity.citadel;

import me.drex.polymerpatcher.PolymerPatcher;
import me.drex.polymerpatcher.dump.ClientOnlyClasses;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Catches a single piece of a model being drawn on its own, rather than the whole model.
 * <p>
 * {@link CitadelDraw} catches a model at the moment its renderer draws it, which covers everything that
 * draws a model. It does not cover a renderer that reaches past the model and draws <em>one piece of
 * it</em> - and they do: Alex's Caves' dinosaur spirit is a tremorsaurus' neck and head and nothing
 * else, drawn straight from the box, so a soul torn out by the extinction spear arrived as empty air.
 * Two of its three kinds are drawn that way.
 * <p>
 * The piece is a class the game refuses to load on a server, so it cannot be patched the ordinary way:
 * the refusal happens before any patcher sees it, and a patch naming it is dropped with a warning. What
 * the refusal does mean is that this mod defines the class itself, out of the mod's own jar - and a
 * class you define is a class you can change on the way past.
 * <p>
 * What is added is one call at the top of its drawing method, saying what is being drawn and with what.
 * Nothing is cancelled and no branch is added, so the method that comes out has the same shape as the
 * one that went in - it is the same bytecode with nine more instructions in front, which is as small a
 * change as this can be made. The drawing itself carries on into a buffer that keeps nothing.
 * <p>
 * Nothing here names a mod. The shape of the method is the whole of the description, and every model
 * piece in every copy of Citadel has it.
 */
public final class CitadelBoxPatch {

    private CitadelBoxPatch() {
    }

    /** The drawing method every model piece has: a pose, a buffer, light, overlay and a colour. */
    private static final String DRAW = "render";
    private static final String DRAW_SHAPE =
        "(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V";

    /** Where the call inserted at the top of it goes. */
    private static final String SINK = "me/drex/polymerpatcher/entity/citadel/CitadelDraw";
    private static final String TAKE = "takeBox";
    private static final String TAKE_SHAPE = "(Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Ljava/lang/Object;)V";

    /** Puts the hook in place for every client-only class defined from here on. */
    public static void install() {
        ClientOnlyClasses.patches = CitadelBoxPatch::patch;
    }

    /**
     * Only the classes that are pieces of a Citadel model. Everything else is handed straight back,
     * which is all but a handful of the hundreds of classes brought in this way.
     */
    private static boolean worthPatching(String name) {
        return name.contains(".citadel.client.model.") && (name.endsWith("ModelBox") || name.endsWith("ModelPart"));
    }

    private static byte @Nullable [] patch(String name, byte[] bytes) {
        if (!worthPatching(name)) {
            return bytes;
        }

        ClassReader reader = new ClassReader(bytes);
        boolean[] done = {false};

        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String method, String shape, String signature, String[] thrown) {
                MethodVisitor visitor = super.visitMethod(access, method, shape, signature, thrown);
                if (!DRAW.equals(method) || !DRAW_SHAPE.equals(shape) || (access & Opcodes.ACC_STATIC) != 0) {
                    return visitor;
                }

                done[0] = true;
                return new MethodVisitor(Opcodes.ASM9, visitor) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        visitVarInsn(Opcodes.ALOAD, 0);
                        visitVarInsn(Opcodes.ALOAD, 1);
                        visitVarInsn(Opcodes.ALOAD, 2);
                        visitMethodInsn(Opcodes.INVOKESTATIC, SINK, TAKE, TAKE_SHAPE, false);
                    }

                    @Override
                    public void visitMaxs(int maxStack, int maxLocals) {
                        super.visitMaxs(Math.max(maxStack, 3), maxLocals);
                    }
                };
            }
        }, 0);

        if (!done[0]) {
            return bytes;
        }

        PolymerPatcher.LOGGER.debug("{} will say when one of its pieces is drawn", name);
        return writer.toByteArray();
    }
}
