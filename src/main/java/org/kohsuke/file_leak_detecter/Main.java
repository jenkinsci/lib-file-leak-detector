package org.kohsuke.file_leak_detecter;

import org.kohsuke.file_leak_detecter.transform.ClassTransformSpec;
import org.kohsuke.file_leak_detecter.transform.CodeGenerator;
import org.kohsuke.file_leak_detecter.transform.MethodAppender;
import org.kohsuke.file_leak_detecter.transform.TransformerImpl;
import org.objectweb.asm.MethodVisitor;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;

/**
 * @author Kohsuke Kawaguchi
 */
public class Main {
    public static void premain(String agentArguments, Instrumentation instrumentation) throws UnmodifiableClassException, IOException {
        System.out.println("Installed");
        TransformerImpl t = new TransformerImpl(
            new ClassTransformSpec("java/io/FileOutputStream",
                new MethodAppender("close","()V") {
                    protected void append(MethodVisitor base) {
                        CodeGenerator.println(base,"Stream closed");
                    }
                },
                new MethodAppender("<init>","(Ljava/io/File;Z)V") {
                    protected void append(MethodVisitor base) {
                        base.visitIntInsn(ALOAD,1);
                        base.visitMethodInsn(INVOKESTATIC,"org/kohsuke/file_leak_detector/Listener","open","(Ljava/io/File;)V");
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
