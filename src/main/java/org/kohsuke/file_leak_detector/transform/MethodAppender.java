package org.kohsuke.file_leak_detector.transform;

import org.kohsuke.asm3.MethodAdapter;
import org.kohsuke.asm3.MethodVisitor;
import static org.kohsuke.asm3.Opcodes.RETURN;

/**
 * {@link MethodTransformSpec} that adds some code right before the return statement.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class MethodAppender extends MethodTransformSpec {
    public MethodAppender(String name, String desc) {
        super(name, desc);
    }

    protected abstract void append(CodeGenerator g);

    @Override
    public MethodVisitor newAdapter(MethodVisitor base) {
        final CodeGenerator cg = new CodeGenerator(base);
        return new MethodAdapter(base) {
            @Override
            public void visitInsn(int opcode) {
                if(opcode==RETURN)
                    append(cg);
                super.visitInsn(opcode);
            }
        };
    }
}
