package org.kohsuke.file_leak_detecter;

import org.kohsuke.file_leak_detecter.transform.ClassTransformSpec;
import org.kohsuke.file_leak_detecter.transform.CodeGenerator;
import org.kohsuke.file_leak_detecter.transform.MethodAppender;
import org.kohsuke.file_leak_detecter.transform.TransformerImpl;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.File;
import java.io.RandomAccessFile;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;

/**
 * @author Kohsuke Kawaguchi
 */
public class Main {
    public static void premain(String agentArguments, Instrumentation instrumentation) throws UnmodifiableClassException, IOException {
        System.out.println("Installed");
        TransformerImpl t = new TransformerImpl(
            newSpec(FileOutputStream.class,"(Ljava/io/File;Z)V"),
            newSpec(FileInputStream.class, "(Ljava/io/File;)V"),
            newSpec(RandomAccessFile.class,"(Ljava/io/File;Ljava/lang/String;)V")
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

    private static ClassTransformSpec newSpec(final Class c, String constructorDesc) {
        return new ClassTransformSpec(c.getName().replace('.','/'),
            new MethodAppender("<init>", constructorDesc) {
                protected void append(CodeGenerator g) {
                    g.invokeAppStatic("org.kohsuke.file_leak_detecter.Listener","open",
                            new Class[]{c, File.class},
                            new int[]{0,1});
                }
            },
            new MethodAppender("close","()V") {
                protected void append(CodeGenerator g) {
                    g.invokeAppStatic("org.kohsuke.file_leak_detecter.Listener","close",
                            new Class[]{c},
                            new int[]{0});
                }
            }
        );
    }
}
