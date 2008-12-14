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
import java.io.File;
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
                    protected void append(CodeGenerator g) {
                        g.println("Stream closed");
                    }
                },
                new MethodAppender("<init>","(Ljava/io/File;Z)V") {
                    protected void append(CodeGenerator g) {
                        Label s = new Label();
                        Label e = new Label();
                        Label h = new Label();
                        g.visitTryCatchBlock(s,e,h,"java/lang/Exception");
                        g.visitLabel(s);
                        // [RESULT] m = ClassLoader.getSystemClassLoadeR().loadClass("org.kohsuke.file_leak_detecter").getDeclaredMethod("open",[...]);
                        g.visitMethodInsn(INVOKESTATIC,"java/lang/ClassLoader","getSystemClassLoader","()Ljava/lang/ClassLoader;");
                        g.ldc("org.kohsuke.file_leak_detecter.Listener");
                        g.invokeVirtual("java/lang/ClassLoader","loadClass","(Ljava/lang/String;)Ljava/lang/Class;");
                        g.ldc("open");
                        g.newArray("Ljava/lang/Class;",2);
                        storeClass(g, 0, FileOutputStream.class);
                        storeClass(g, 1, File.class);

                        g.invokeVirtual("java/lang/Class","getDeclaredMethod","(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");

                        // [RESULT] m.invoke(null,new Object[]{this,file})
                        g._null();
                        g.newArray("Ljava/lang/Object;",2);

                        g.dup();
                        g.iconst(0);
                        g.aload(0);
                        g.aastore();

                        g.dup();
                        g.iconst(1);
                        g.aload(1);
                        g.aastore();

                        g.visitMethodInsn(INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
                        g.pop();
                        g.visitInsn(RETURN);

                        g.visitLabel(e);
                        g.visitLabel(h);

                        // [RESULT] catch(e) { System.out.println(e); }
                        g.visitFieldInsn(GETSTATIC,"java/lang/System","out","Ljava/io/PrintStream;");
                        g.visitInsn(SWAP);
                        g.invokeVirtual("java/io/PrintStream","println","(Ljava/lang/Object;)V");
                    }

                    private void storeClass(CodeGenerator g, int idx, Class<?> type) {
                        g.dup();
                        g.iconst(idx);
                        g.ldc(type);
                        g.aastore();
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
