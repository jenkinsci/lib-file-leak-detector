package org.kohsuke.file_leak_detecter;

import org.kohsuke.file_leak_detecter.transform.ClassTransformSpec;
import org.kohsuke.file_leak_detecter.transform.CodeGenerator;
import org.kohsuke.file_leak_detecter.transform.MethodAppender;
import org.kohsuke.file_leak_detecter.transform.TransformerImpl;
import org.objectweb.asm.Label;
import static org.objectweb.asm.Opcodes.*;

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
                    protected void append(CodeGenerator base) {
                        base.println("Stream closed");
                    }
                },
                new MethodAppender("<init>","(Ljava/io/File;Z)V") {
                    protected void append(CodeGenerator base) {
                        Label s = new Label();
                        Label e = new Label();
                        Label h = new Label();
                        base.visitTryCatchBlock(s,e,h,"java/lang/Exception");
                        base.visitLabel(s);
                        // m = ClassLoader.getSystemClassLoadeR().loadClass("org.kohsuke.file_leak_detecter").getDeclaredMethod("open",[]);
                        base.visitMethodInsn(INVOKESTATIC,"java/lang/ClassLoader","getSystemClassLoader","()Ljava/lang/ClassLoader;");
                        base.visitLdcInsn("org.kohsuke.file_leak_detecter");
                        base.visitMethodInsn(INVOKEVIRTUAL,"java/lang/ClassLoader","loadClass","(Ljava/lang/String;)Ljava/lang/Class;");
                        base.visitLdcInsn("open");
                        base.newArray("Ljava/lang/Class;",0);
                        base.visitMethodInsn(INVOKEVIRTUAL,"java/lang/Class","getDeclaredMethod","(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");

                        // m.invoke(null,new Object[]{file})
                        base._null();
                        base.newArray("Ljava/lang/Object;",1);

                        base.dup();
                        base.iconst(0);
                        base.aload(1);
                        base.aastore();

                        base.invokeVirtualVoid("java/lang/reflect/Method","invoke","(Ljava/io/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
                        base.visitInsn(RETURN);

                        base.visitLabel(e);
                        base.visitLabel(h);
                        base.println("error!");
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
