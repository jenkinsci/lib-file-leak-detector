package org.kohsuke.file_leak_detecter;

import org.kohsuke.file_leak_detecter.transform.ClassTransformSpec;
import org.kohsuke.file_leak_detecter.transform.CodeGenerator;
import org.kohsuke.file_leak_detecter.transform.MethodAppender;
import org.kohsuke.file_leak_detecter.transform.TransformerImpl;

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
                new MethodAppender("<init>","(Ljava/io/File;Z)V") {
                    protected void append(CodeGenerator g) {
                        g.invokeAppStatic("org.kohsuke.file_leak_detecter.Listener","open",
                                new Class[]{FileOutputStream.class,File.class},
                                new int[]{0,1});
                    }
                },
                new MethodAppender("close","()V") {
                    protected void append(CodeGenerator g) {
                        g.invokeAppStatic("org.kohsuke.file_leak_detecter.Listener","close",
                                new Class[]{FileOutputStream.class},
                                new int[]{0});
                    }
                }
            ),
            new ClassTransformSpec("java/io/FileInputStream",
                new MethodAppender("<init>","(Ljava/io/File;)V") {
                    protected void append(CodeGenerator g) {
                        g.invokeAppStatic("org.kohsuke.file_leak_detecter.Listener","open",
                                new Class[]{FileInputStream.class,File.class},
                                new int[]{0,1});
                    }
                },
                new MethodAppender("close","()V") {
                    protected void append(CodeGenerator g) {
                        g.invokeAppStatic("org.kohsuke.file_leak_detecter.Listener","close",
                                new Class[]{FileInputStream.class},
                                new int[]{0});
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
