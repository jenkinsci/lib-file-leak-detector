package org.kohsuke.file_leak_detector.transform;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * {@link MethodTransformSpec} that adds some code right before the return statement.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class MethodAppender extends MethodTransformSpec {
    public MethodAppender(String name, String desc) {
        super(name, desc);
    }

    /**
     * Generates code to be appended right before the return statement.
     */
    protected abstract void append(CodeGenerator g);

    @Override
    public MethodVisitor newAdapter(
            MethodVisitor base, int access, String name, String desc, String signature, String[] exceptions) {
        final CodeGenerator cg = new CodeGenerator(base);
        return new MethodVisitor(Opcodes.ASM9, base) {
            @Override
            public void visitInsn(int opcode) {
                switch (opcode) {
                    case Opcodes.RETURN:
                    case Opcodes.ARETURN:
                        append(cg);
                        break;
                    default:
                        // ignored
                }
                super.visitInsn(opcode);
            }
        };
    }
}
