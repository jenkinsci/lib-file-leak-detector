package org.kohsuke.file_leak_detecter.transform;

import org.objectweb.asm.MethodAdapter;
import org.objectweb.asm.MethodVisitor;
import static org.objectweb.asm.Opcodes.RETURN;

/**
 * {@link MethodTransformSpec} that adds some code right before the return statement.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class MethodAppender extends MethodTransformSpec {
    public MethodAppender(String name, String desc) {
        super(name, desc);
    }

    protected abstract void append(MethodVisitor base);

    @Override
    public MethodVisitor newAdapter(final MethodVisitor base) {
        return new MethodAdapter(base) {
            @Override
            public void visitInsn(int opcode) {
                if(opcode==RETURN) {
                    append(base);
                }
                super.visitInsn(opcode);
            }
        };
    }
}
