package org.kohsuke.file_leak_detecter;

import org.objectweb.asm.MethodAdapter;
import org.objectweb.asm.MethodVisitor;
import static org.objectweb.asm.Opcodes.*;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * @author Kohsuke Kawaguchi
 */
public class Main {
    public static void premain(String agentArguments, Instrumentation instrumentation) throws UnmodifiableClassException, IOException {
        System.out.println("Installed");
        TransformerImpl t = new TransformerImpl(
            new ClassTransformSpec("java/io/FileOutputStream",
                new MethodTransformSpec("close","()V") {
                    @Override
                    public MethodVisitor newAdapter(MethodVisitor base) {
                        return new MethodAdapter(base) {
                            @Override
                            public void visitInsn(int opcode) {
                                if(opcode==RETURN) {
                                    super.visitFieldInsn(GETSTATIC,"java/lang/System","out","Ljava/io/PrintStream;");
                                    super.visitLdcInsn("Stream closed");
                                    super.visitMethodInsn(INVOKEVIRTUAL,"java/io/PrintStream","println","(Ljava/lang/String;)V");
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                }
            )
        );
        instrumentation.addTransformer(t,true);
        instrumentation.retransformClasses(
                FileInputStream.class,
                FileOutputStream.class);

        // test code
        FileOutputStream o = new FileOutputStream("target/dummy");
        o.write("abc".getBytes());
        o.close();
    }
}
