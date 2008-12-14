package org.kohsuke.file_leak_detecter.transform;

import org.objectweb.asm.MethodAdapter;
import org.objectweb.asm.MethodVisitor;
import static org.objectweb.asm.Opcodes.*;

/**
 * Convenience method to generate bytecode.
 *
 * @author Kohsuke Kawaguchi
 */
public class CodeGenerator extends MethodAdapter {
    public CodeGenerator(MethodVisitor mv) {
        super(mv);
    }

    public void println(String msg) {
        super.visitFieldInsn(GETSTATIC,"java/lang/System","out","Ljava/io/PrintStream;");
        super.visitLdcInsn(msg);
        super.visitMethodInsn(INVOKEVIRTUAL,"java/io/PrintStream","println","(Ljava/lang/String;)V");
    }

    public void _null() {
        super.visitInsn(ACONST_NULL);
    }

    public void newArray(String type, int size) {
        iconst(size);
        super.visitTypeInsn(ANEWARRAY,type);
    }

    public void iconst(int i) {
        if(i<=5)
            super.visitInsn(ICONST_0+i);
        else
            super.visitLdcInsn(i);
    }

    public void dup() {
        super.visitInsn(DUP);
    }

    public void aastore() {
        super.visitInsn(AASTORE);
    }

    public void aload(int i) {
        super.visitIntInsn(ALOAD,i);
    }

    public void pop() {
        super.visitInsn(POP);
    }

    public void invokeVirtualVoid(String owner, String name, String desc) {
        super.visitMethodInsn(INVOKEVIRTUAL, owner, name, desc);
        pop();
    }
}
