package org.kohsuke.file_leak_detecter.transform;

import org.objectweb.asm.MethodVisitor;
import static org.objectweb.asm.Opcodes.GETSTATIC;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;

/**
 * Convenience method to generate bytecode.
 *
 * @author Kohsuke Kawaguchi
 */
public class CodeGenerator {
    public static void println(MethodVisitor base, String msg) {
        base.visitFieldInsn(GETSTATIC,"java/lang/System","out","Ljava/io/PrintStream;");
        base.visitLdcInsn(msg);
        base.visitMethodInsn(INVOKEVIRTUAL,"java/io/PrintStream","println","(Ljava/lang/String;)V");
    }
}
